// ESP32 / IoT device agent: checks in with the kiosk server so the device shows on the dashboard.
// Arduino IDE, board "ESP32 Dev Module". Fill in the Wi-Fi and server details below and upload.
// Any other device that can send an HTTP POST can check in the same way: see /api/report in server/server.js.
#include <WiFi.h>
#include <HTTPClient.h>

const char* WIFI_SSID = "your-wifi";
const char* WIFI_PASS = "your-password";
const char* SERVER = "http://192.168.68.50:8080";
const char* DEVICE_KEY = "change-me-device-key";
const char* NAME = "ESP32 sensor";
const unsigned long EVERY_MS = 30000;

String deviceId;

void setup() {
  Serial.begin(115200);
  deviceId = "iot-" + String((uint32_t)(ESP.getEfuseMac() >> 16), HEX) + String((uint32_t)ESP.getEfuseMac(), HEX);
  WiFi.begin(WIFI_SSID, WIFI_PASS);
}

void loop() {
  if (WiFi.status() == WL_CONNECTED) {
    HTTPClient http;
    http.begin(String(SERVER) + "/api/report");
    http.addHeader("Content-Type", "application/json");
    http.addHeader("X-Device-Key", DEVICE_KEY);
    String body = "{\"id\":\"" + deviceId + "\",\"type\":\"iot\",\"deviceName\":\"" + NAME +
                  "\",\"model\":\"ESP32\",\"os\":\"Arduino\",\"localIp\":\"" + WiFi.localIP().toString() +
                  "\",\"uptime\":" + String(millis() / 1000) +
                  ",\"info\":{\"Wi-Fi signal\":\"" + String(WiFi.RSSI()) + " dBm\",\"Free memory\":\"" +
                  String(ESP.getFreeHeap() / 1024) + " KB\"}}";
    int code = http.POST(body);
    String reply = http.getString();
    http.end();
    Serial.printf("check-in %d\n", code);
    if (code == 200 && reply.indexOf("\"reboot\"") >= 0) ESP.restart();
  }
  delay(EVERY_MS);
}
