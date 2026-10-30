# Точки входа Android подключаются по манифесту; release-сборка не должна их вырезать.
-keep class com.example.tunemileage.service.NotificationWatchService { *; }
-keep class com.example.tunemileage.service.MediaWatchService { *; }
-keep class com.example.tunemileage.receiver.BootReceiver { *; }

# org.json — часть платформы, отдельного keep не требуется.
