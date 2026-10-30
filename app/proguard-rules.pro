# Точки входа Android подключаются по манифесту; release-сборка не должна их вырезать.
-keep class com.example.carmusicmeter.service.NotificationWatchService { *; }
-keep class com.example.carmusicmeter.service.MediaWatchService { *; }
-keep class com.example.carmusicmeter.receiver.BootReceiver { *; }

# org.json — часть платформы, отдельного keep не требуется.
