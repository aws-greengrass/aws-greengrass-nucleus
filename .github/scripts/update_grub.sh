#!/bin/bash

# Check if running as root, if not, switch to root
if [ "$EUID" -ne 0 ]; then
    echo "Not running as root, switching to root..."
    exec sudo su -c "$0 $*"
    exit $?
fi

# Add parameters to GRUB_CMDLINE_LINUX while preserving existing content
sed -i 's/GRUB_CMDLINE_LINUX="\(.*\)"/GRUB_CMDLINE_LINUX="\1 cgroup_enable=memory systemd.unified_cgroup_hierarchy=0"/' /etc/default/grub

# Update GRUB
grub-mkconfig -o /boot/grub/grub.cfg

# Check if update was successful
if [ $? -eq 0 ]; then
    echo "GRUB updated successfully. Rebooting..."
    reboot
else
    echo "Error updating GRUB"
    exit 1
fi
