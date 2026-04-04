#!/system/bin/sh
MODDIR=${0%/*}
ABI=$(getprop ro.product.cpu.abi)

# Map ABI to our directory names
case "$ABI" in
    arm64*) ARCH="arm64-v8a" ;;
    armeabi*|armv7*) ARCH="armeabi-v7a" ;;
    x86_64) ARCH="x86_64" ;;
    x86) ARCH="x86" ;;
    *) ARCH="arm64-v8a" ;; # fallback
esac

DAEMON="$MODDIR/system/bin/ftyd"

# Kill existing
if [ -f /data/local/tmp/ftyd.pid ]; then
    kill $(cat /data/local/tmp/ftyd.pid) 2>/dev/null
    rm -f /data/local/tmp/ftyd.pid
fi

# Start daemon with abstract socket (bypasses filesystem/SELinux permissions)
if [ -x "$DAEMON" ]; then
    $DAEMON -s @ftyd
    log -t ftyd "Started ftyd daemon ($ARCH) on abstract socket @ftyd"
else
    log -t ftyd "ERROR: ftyd binary not found at $DAEMON"
fi
