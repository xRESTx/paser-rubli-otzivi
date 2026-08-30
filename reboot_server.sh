#!/bin/bash
# Скрипт для перезагрузки сервера
# Использование: ./reboot_server.sh

# Логируем перезагрузку
LOG_FILE="/var/log/server_reboot.log"
echo "$(date '+%Y-%m-%d %H:%M:%S') - Server reboot initiated by cron" >> "$LOG_FILE"

# Перезагружаем сервер
# Используем /sbin/reboot если доступен, иначе sudo reboot
if [ -f /sbin/reboot ]; then
    /sbin/reboot
elif command -v sudo &> /dev/null; then
    sudo reboot
else
    shutdown -r now
fi

