/*******************************************************************************
 * Copyright (c) 2011 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * The scan engine idea is based on the "ScanEngine" developed
 * by the Software Services Group (SSG),  Advanced Photon Source,
 * Argonne National Laboratory,
 * Copyright (c) 2011 , UChicago Argonne, LLC.
 *
 * This implementation, however, contains no SSG "ScanEngine" source code
 * and is not endorsed by the SSG authors.
 ******************************************************************************/
package org.csstudio.scan.server.internal;

import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.csstudio.scan.command.LoopCommand;
import org.csstudio.scan.command.ScanCommand;
import org.csstudio.scan.commandimpl.WaitForDevicesCommand;
import org.csstudio.scan.commandimpl.WaitForDevicesCommandImpl;
import org.csstudio.scan.data.ScanData;
import org.csstudio.scan.data.ScanSample;
import org.csstudio.scan.device.Device;
import org.csstudio.scan.device.DeviceContext;
import org.csstudio.scan.device.DeviceInfo;
import org.csstudio.scan.log.DataLog;
import org.csstudio.scan.log.DataLogFactory;
import org.csstudio.scan.server.Scan;
import org.csstudio.scan.server.ScanCommandImpl;
import org.csstudio.scan.server.ScanContext;
import org.csstudio.scan.server.ScanInfo;
import org.csstudio.scan.server.ScanState;

/** Scan that can be executed: Commands, device context, state
 *
 *  <p>Combines a {@link DeviceContext} with {@link ScanContextImpl}ementations
 *  and can execute them.
 *  When a command is executed, it receives a {@link ScanContext} view
 *  of the scan for limited access to the devices, data logger etc.
 *
 *  @author Kay Kasemir
 */
@SuppressWarnings("nls")
public class ExecutableScan extends ScanContext
{
    final private Scan scan;

    final private List<ScanCommandImpl<?>> pre_scan, implementations, post_scan;

    /** State of this scan
     *  SYNC on this for access
     */
    private ScanState state = ScanState.Idle;

    private volatile String error = null;

    private volatile long start_ms = 0;

    private volatile long end_ms = 0;

    final private long total_work_units;

    /** Data logger, non-null while when executing the scan */
    private volatile DataLog data_logger = null;

    private volatile ScanCommandImpl<?> current_command = null;

    /** Initialize
     *  @param name User-provided name for this scan
     *  @param devices {@link DeviceContext} to use for scan
     *  @param implementations Commands to execute in this scan
     *  @throws Exception on error (cannot access log, ...)
     */
    public ExecutableScan(final String name, final DeviceContext devices, ScanCommandImpl<?>... implementations) throws Exception
    {
        this(name, devices,
            Collections.<ScanCommandImpl<?>>emptyList(),
            Arrays.asList(implementations),
            Collections.<ScanCommandImpl<?>>emptyList());
    }

    /** Initialize
     *  @param name User-provided name for this scan
     *  @param devices {@link DeviceContext} to use for scan
     *  @param pre_scan Commands to execute before the 'main' section of the scan
     *  @param implementations Commands to execute in this scan
     *  @param post_scan Commands to execute before the 'main' section of the scan
     *  @throws Exception on error (cannot access log, ...)
     */
    public ExecutableScan(final String name, final DeviceContext devices,
            final List<ScanCommandImpl<?>> pre_scan,
            final List<ScanCommandImpl<?>> implementations,
            final List<ScanCommandImpl<?>> post_scan) throws Exception
    {
    	super(devices);
    	scan = DataLogFactory.createDataLog(name);
        this.pre_scan = pre_scan;
        this.implementations = implementations;
        this.post_scan = post_scan;

        // Assign addresses to all commands,
        // determine work units
        long address = 0;
        long work_units = 0;
        for (ScanCommandImpl<?> impl : implementations)
        {
            address = impl.setAddress(address);
        	work_units += impl.getWorkUnits();
        }
        total_work_units = work_units;
    }

    /** @return Unique scan identifier (within JVM of the scan engine) */
    public long getId()
    {
        return scan.getId();
    }

    /** @return Scan name */
    public String getName()
    {
        return scan.getName();
    }

    /** @return Info about current state of this scan */
    public ScanInfo getScanInfo()
    {
        final ScanCommandImpl<?> command = current_command;

        final long address = command == null ? -1 : command.getCommand().getAddress();
        final String command_name;
        final ScanState state;
        synchronized (this)
        {
            state = this.state;
        }
        if (state == ScanState.Finished)
        {
            command_name = "- end -";
        }
        else if (command != null)
            command_name = command.toString();
        else
            command_name = "";
        return new ScanInfo(scan.getId(), scan.getName(), scan.getCreated(), state, error, computeRuntime(), work_performed.get(), total_work_units, address, command_name);
    }

    /** @return Runtime of this scan (so far) in millisecs. 0 if not started */
    private long computeRuntime()
    {
        final long start = start_ms;
        final long end = end_ms;
        if (end > 0)
            return end - start;
        if (start > 0)
            return System.currentTimeMillis() - start_ms;
        return 0;
    }

    /** @return Commands executed by this scan */
    public List<ScanCommand> getScanCommands()
    {
        // Fetch underlying commands for implementations
        final List<ScanCommand> commands = new ArrayList<ScanCommand>(implementations.size());
        for (ScanCommandImpl<?> impl : implementations)
            commands.add(impl.getCommand());
        return commands;
    }

    /** @param address Command address
     *  @return ScanCommand with that address
     *  @throws RemoteException when not found
     */
    public ScanCommand getCommandByAddress(final long address) throws RemoteException
    {
        final ScanCommand found = findCommandByAddress(getScanCommands(), address);
        if (found == null)
            throw new RemoteException("Invalid command address " + address);
        return found;
    }

    /** Recursively search for command by address
     *  @param commands Command list
     *  @param address Desired command address
     *  @return Command with that address or <code>null</code>
     */
    private ScanCommand findCommandByAddress(final List<ScanCommand> commands,
            final long address)
    {
        for (ScanCommand command : commands)
        {
            if (command.getAddress() == address)
                return command;
            else if (command instanceof LoopCommand)
            {
                final LoopCommand loop = (LoopCommand) command;
                final ScanCommand found = findCommandByAddress(loop.getBody(), address);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    /** Attempt to update a command parameter to a new value
     *  @param address Address of the command
     *  @param property_id Property to update
     *  @param value New value for the property
     *  @throws RemoteException on error
     */
    public void updateScanProperty(final long address, final String property_id,
        final Object value) throws RemoteException
    {
        final ScanCommand command = getCommandByAddress(address);
        try
        {
            command.setProperty(property_id, value);
        }
        catch (Exception ex)
        {
            throw new RemoteException("Cannot update " + property_id + " of " +
                    command.getCommandName(), ex);
        }
    }

    /** Obtain devices used by this scan.
     *
     *  <p>Note that the result can differ before and
     *  after the scan gets executed because devices
     *  are added to the device context as needed.
     *
     *  @return Devices used by this scan
     */
    public Device[] getDevices()
    {
        return devices.getDevices();
    }

    /** {@inheritDoc} */
    @Override
    public long getNextScanDataSerial()
    {
        if (data_logger == null)
            return -1;
    	return data_logger.getNextScanDataSerial();
    }

    /** @return Last logged scan data serial. -1 if nothing has been logged. 0 if scan is not active */
    public long getLastScanDataSerial()
    {
        if (data_logger == null)
            return 0;
        return data_logger.getLastScanDataSerial();
    }

    /** {@inheritDoc} */
	@Override
	public ScanData getScanData() throws Exception
	{
	    // Allow calls non-active scan where data_logger == null
	    final DataLog logger = DataLogFactory.getDataLog(scan);
	    try
	    {
	        return logger.getScanData();
	    }
	    finally
	    {
	        logger.close();
	    }
	}

    /** {@inheritDoc} */
    @Override
    public void logSample(final ScanSample sample) throws Exception
    {
        if (data_logger == null)
            throw new IllegalStateException("Cannot log while scan is " + state);
        data_logger.log(sample);
    }

    /** Execute all commands on the scan,
     *  turning exceptions into a 'Failed' scan state.
     */
    public void execute()
    {
        try
        {
            // Open logger for execution of scan
            data_logger = DataLogFactory.getDataLog(scan);
            execute_or_die_trying();
        }
        catch (InterruptedException ex)
        {
        	synchronized (this)
        	{
        		state = ScanState.Aborted;
        	}
            error = "Interrupted";
        }
        catch (Exception ex)
        {
        	synchronized (this)
        	{
        		state = ScanState.Failed;
        	}
            error = ex.getMessage();
        }
        // Close data logger
        final DataLog copy = data_logger;
        data_logger = null;
        copy.close();
    }

    /** Execute all commands on the scan,
     *  passing exceptions back up.
     *  @throws Exception on error
     */
    private void execute_or_die_trying() throws Exception
    {
    	synchronized (this)
    	{
	        // Was scan aborted before it ever got to run?
	        if (state == ScanState.Aborted)
	            return;
	        // Otherwise expect 'Idle'
	        if (state != ScanState.Idle)
	            throw new IllegalStateException("Cannot run Scan that is " + state);
	        state = ScanState.Running;
    	}
    	start_ms = System.currentTimeMillis();

        // Inspect all commands before executing them:
        // Add devices that are not available (via alias)
        // in the device context
        final Set<String> required_devices = new HashSet<String>();
        for (ScanCommandImpl<?> command : implementations)
        {
            for (String device_name : command.getDeviceNames())
                required_devices.add(device_name);
        }
        // Add required devices
        for (String device_name : required_devices)
        {
            try
            {
                if (devices.getDeviceByAlias(device_name) != null)
                    continue;
            }
            catch (Exception ex)
            {   // Add PV device, no alias, for unknown device
                devices.addPVDevice(new DeviceInfo(device_name, device_name, true, true));
            }
        }

        // Start Devices
        devices.startDevices();

        // Execute commands
        try
        {
            execute(new WaitForDevicesCommandImpl(new WaitForDevicesCommand(devices.getDevices())));
            try
            {
                // Execute pre-scan commands
                execute(pre_scan);

                // Reset work step counter to only count the 'main' commands
                work_performed.set(0);

                // Execute the submitted commands
                execute(implementations);

                // Successful finish
                synchronized (this)
                {
                	state = ScanState.Finished;
                }
            }
            finally
            {
                // Try post-scan commands even if submitted commands ran into problems or was aborted.
            	// Save the state before going back to Running for post commands.
            	final long saved_steps = work_performed.get();
                final ScanState saved_state;
                synchronized (this)
                {
                	saved_state = state;
                	state = ScanState.Running;
                }

                execute(post_scan);

                // Restore saved state
                work_performed.set(saved_steps);
                synchronized (this)
                {
                	state = saved_state;
                }
            }
        }
        finally
        {
            current_command = null;
            end_ms = System.currentTimeMillis();
            // Stop devices
            devices.stopDevices();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void execute(final List<ScanCommandImpl<?>> commands) throws Exception
    {
        for (ScanCommandImpl<?> command : commands)
        {
        	synchronized (this)
        	{
	            if (state != ScanState.Running  &&
	                state != ScanState.Paused)
	                return;
        	}
            execute(command);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void execute(final ScanCommandImpl<?> command) throws Exception
    {
        synchronized (this)
        {
            while (state == ScanState.Paused)
            {
                wait();
            }
        }
        try
        {
            current_command = command;
    		Logger.getLogger(getClass().getName()).log(Level.FINE, "{0}", command);
            command.execute(this);
        }
        catch (InterruptedException ex)
        {
            final String message = "Command aborted: " + command.toString();
            Logger.getLogger(getClass().getName()).log(Level.INFO, message, ex);
            throw ex;
        }
        catch (Exception ex)
        {
            final String message = "Command failed: " + command.toString();
            Logger.getLogger(getClass().getName()).log(Level.WARNING, message, ex);
            throw ex;
        }
    }

    /** Pause execution of a currently executing scan */
    public synchronized void pause()
    {
        if (state == ScanState.Running)
            state = ScanState.Paused;
    }

    /** Resume execution of a paused scan */
    public synchronized void resume()
    {
        if (state == ScanState.Paused)
        {
            state = ScanState.Running;
            notifyAll();
        }
    }

    /** Ask for execution to stop */
    synchronized void abort()
    {
        if (! state.isDone())
            state = ScanState.Aborted;
        notifyAll();
    }

    // Hash by ID
    @Override
    public int hashCode()
    {
        return scan.hashCode();
    }

    // Compare by ID
    @Override
    public boolean equals(final Object obj)
    {
        if (! (obj instanceof ExecutableScan))
            return false;
        final ExecutableScan other = (ExecutableScan) obj;
        return scan.equals(other.scan);
    }

	@Override
    public String toString()
    {
	    return scan.toString();
    }
}