package org.csstudio.utility.jmssendcmd;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import org.csstudio.apputil.args.ArgParser;
import org.csstudio.apputil.args.BooleanOption;
import org.csstudio.apputil.args.EDMOption;
import org.csstudio.apputil.args.StringOption;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;

/** Eclipse application for JMS 'Send' command
 *  @author Kay Kasemir
 *  @author Delphy Armstrong
 */
@SuppressWarnings("nls")
public class Application implements IApplication
{
    final private static String DEFAULT_URL = "tcp://localhost:61616";
    final private static String DEFAULT_TOPIC = "TEST";
    final private static String DEFAULT_TYPE = "log";
    private static final String DEFAULT_APP = "JMSSender";
    private String application;
    private String type;
   private boolean edm_mode;
    
    /** @see IApplication */
    public Object start(IApplicationContext context) throws Exception
    {
        // Create parser for arguments and run it.
        final String args[] =
            (String []) context.getArguments().get("application.args");
        final ArgParser parser = new ArgParser();
        final StringOption url = new StringOption(parser,
                "-url", "JMS Server URL (default: " + DEFAULT_URL + ")", DEFAULT_URL);
        final StringOption jms_user = new StringOption(parser,
                "-jms_user", "JMS User Name", null);
        final StringOption jms_pass = new StringOption(parser,
                "-jms_pass", "JMS Password", null);
        final StringOption topic = new StringOption(parser,
                "-topic", "JMS Topic (default: " + DEFAULT_TOPIC + ")", DEFAULT_TOPIC);
        final StringOption type = new StringOption(parser,
                "-type", "Message type (default: " + DEFAULT_TYPE + ")", DEFAULT_TYPE);
        final StringOption app = new StringOption(parser,
                "-app", "Application type (default: " + DEFAULT_APP + ")", DEFAULT_APP);
        final StringOption text = new StringOption(parser,
                "-text", "Send given text (default: read from stdin)", null);
        final BooleanOption edm_mode = new BooleanOption(parser,
              "-edm_mode", "Parse EDM 'put' log formatted input");
        final BooleanOption help = new BooleanOption(parser,
                "-h", "Help");
  
        try
        {
            parser.parse(args);
        }
        catch (Exception ex)
        {
            System.err.println("Error: " + ex.getMessage());
            System.err.println(parser.getHelp());
            return IApplication.EXIT_OK;
        }
        
        if (help.get())
        {
            System.out.println(parser.getHelp());
            return IApplication.EXIT_OK;
        }
        
        System.out.println("URL        : " + url.get());
        System.out.println("Topic      : " + topic.get());
        System.out.println("Type       : " + type.get());
        System.out.println("Application: " + app.get());
        this.type = type.get();
        application = app.get();
        this.edm_mode = edm_mode.get();
        try
        {
            final JMSSender sender = new JMSSender(url.get(), jms_user.get(),
                    jms_pass.get(), topic.get());
            if (text.get() != null)
                sender.send(type.get(), application, text.get(), false);
            else
                sendMsgFromInput(sender);
            sender.disconnect();
        }
        catch (Exception ex)
        {
            ex.printStackTrace();
        }
        
        return IApplication.EXIT_OK;
    }

    /** Read message text from stdin, send to JMS
     *  @param sender JMSSender
     */
    private void sendMsgFromInput(final JMSSender sender)
    {
        final BufferedReader in =
            new BufferedReader(new InputStreamReader(System.in));
        try
        {
            System.out.println("Enter message lines. Ctrl-D to exit.");
            while (true)
            {
                System.out.print(">");
                final String text = in.readLine();
                if (text == null)
                    break;
                sender.send(type, application, text, edm_mode);
            }
        }
        catch (Exception ex)
        {
            ex.printStackTrace();
        }
    }

    /** @see IApplication */
    public void stop()
    {
        // Ignore
    }
}
