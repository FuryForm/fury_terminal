#!/system/bin/sh
# FuryTerminal Daemon Magisk Module Installer

SKIPUNZIP=1

ABI=$(getprop ro.product.cpu.abi)
case "$ABI" in
    arm64*) ARCH="arm64-v8a" ;;
    armeabi*|armv7*) ARCH="armeabi-v7a" ;;
    x86_64) ARCH="x86_64" ;;
    x86) ARCH="x86" ;;
    *) abort "Unsupported architecture: $ABI" ;;
esac

ui_print "- Architecture: $ABI ($ARCH)"

# Extract all files to MODPATH first
unzip -o "$ZIPFILE" -d "$MODPATH"

# Move the correct architecture binary into system/bin
mkdir -p "$MODPATH/system/bin"

if [ -f "$MODPATH/bin/$ARCH/ftyd" ]; then
    mv "$MODPATH/bin/$ARCH/ftyd" "$MODPATH/system/bin/ftyd"
    ui_print "- Installed ftyd for $ARCH"
else
    abort "ftyd binary not found for $ARCH in ZIP"
fi

# Clean up extracted bin/ directory (other ABIs we don't need)
rm -rf "$MODPATH/bin"
# Clean up installer files not needed at runtime
rm -f "$MODPATH/customize.sh"
rm -rf "$MODPATH/META-INF"

# Set permissions
set_perm_recursive $MODPATH 0 0 0755 0644
set_perm $MODPATH/system/bin/ftyd 0 0 0755
set_perm $MODPATH/service.sh 0 0 0755
