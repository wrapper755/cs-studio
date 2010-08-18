#!/bin/sh
#
# Script that triggers a build of "everything"
#
# In principle, this should be an ANT build.xml,
# but the ant copy tasks were really slow compared to rsync.
# Maybe use a 'system' ant task to call rsync?
# In any case, this one is good enough for now.
#
# Kay Kasemir

cd /Kram/MerurialRepos/cs-studio/products/SNS/product/org.csstudio.sns.updatesite

source settings.sh

# Fetch new copy of sources
ant clean

echo Fetching sources
ant get_sources

# Build products and optional feature
for prod in config_build_Basic_CSS config_build_SNS_CSS config_build_optional
do
    echo $prod
    (cd $prod; sh build.sh)
    echo Done with $prod
done

OK=1
# Each build log contains 2(!) "BUILD SUCCESSFUL" lines
for prod in config_build_Basic_CSS config_build_SNS_CSS config_build_optional
do
    if [ `cat $prod/build.log | grep -c "BUILD SUCCESSFUL"` -eq 2 ]
    then
        echo OK: $prod
    else
        echo Build failed: $prod
        OK=0
    fi
done

# On success, fetch/patch the zip files
function patch_product
{
    # Args: original-zip-name  product-name executable final-zip-name
    orig=$1
    product=$2
    exe=$3
    final=$4
    
    # Unzip the file generated by headless build
    unzip -q $orig
    
	# With a headless build in 3.5, the OS X and Linux launchers were not
	# marked executable. https://bugs.eclipse.org/bugs/show_bug.cgi?id=260844 ?
	# With 3.6, this seems no longer necessary, but it can't hurt, either.
    chmod +x $product/$exe

	# Create dropins directory
	# Done via p2.inf, but can't hurt to assert it's there, either
	mkdir -p $product/dropins
    
	# Enable dropins directory
	# In configuration/org.eclipse.equinox.simpleconfigurator/bundles.info,
	# the org.eclipse.equinox.p2.reconciler.dropins must be set to
	# start automatically.
	# Unclear how to do that in p2.inf, so using perl
    perl -p -i -e 's/(org\.eclipse\.equinox\.p2\.reconciler\.dropins,.+),false/\1,true/;' $product/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info    


	# * Remove readme/readme_eclipse.html 
	# Eclipse 3.6 started to create this directory in the product.
	rm -rf $product/readme

    # Create new ZIP
    rm -f $final
    zip -qr $final $product
    
    # Cleanup
    rm -rf $product
    rm $orig
    
    echo Created $final
}

if [ $OK = 1 ]
then
    echo Collecting ZIP files
    mkdir -p apps
    
    ## Basic EPICS
    patch_product build/I.epics_css_$VERSION/epics_css_$VERSION-macosx.carbon.x86.zip CSS_EPICS_$VERSION css.app/Contents/MacOS/css apps/epics_css_$VERSION-macosx.carbon.x86.zip
    patch_product build/I.epics_css_$VERSION/epics_css_$VERSION-linux.gtk.x86.zip     CSS_EPICS_$VERSION css                        apps/epics_css_$VERSION-linux.gtk.x86.zip
    patch_product build/I.epics_css_$VERSION/epics_css_$VERSION-win32.win32.x86.zip   CSS_EPICS_$VERSION css.exe                    apps/epics_css_$VERSION-win32.win32.x86.zip

    ## SNS CSS
    # OS X
    patch_product build/I.sns_css_$VERSION/sns_css_$VERSION-macosx.carbon.x86.zip   CSS_$VERSION    css.app/Contents/MacOS/css    apps/sns_css_$VERSION-macosx.carbon.x86.zip
	patch_product build/I.sns_css_$VERSION/sns_css_$VERSION-linux.gtk.x86.zip       CSS_$VERSION    css                           apps/sns_css_$VERSION-linux.gtk.x86.zip
	patch_product build/I.sns_css_$VERSION/sns_css_$VERSION-win32.win32.x86.zip     CSS_$VERSION    css.exe                       apps/sns_css_$VERSION-win32.win32.x86.zip

    ## Optional feature is already in buildRepo

    ## Source code
    ant zip_sources
fi

