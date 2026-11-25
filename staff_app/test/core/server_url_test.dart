import 'package:flutter_test/flutter_test.dart';
import 'package:staff_app/core/config/server_url.dart';

void main() {
  group('normalizeServerUrl', () {
    test('thêm http:// và bỏ dấu / cuối', () {
      expect(
        normalizeServerUrl('192.168.1.20:8080/'),
        equals('http://192.168.1.20:8080'),
      );
      expect(
        normalizeServerUrl('  https://api.example.com/  '),
        equals('https://api.example.com'),
      );
    });

    test('giữ lại đường dẫn con', () {
      expect(
        normalizeServerUrl('http://host:8080/qr/'),
        equals('http://host:8080/qr'),
      );
    });

    test('từ chối chuỗi rỗng, scheme lạ, query và fragment', () {
      expect(
        () => normalizeServerUrl('  '),
        throwsA(isA<ServerUrlException>()),
      );
      expect(
        () => normalizeServerUrl('ftp://host'),
        throwsA(isA<ServerUrlException>()),
      );
      expect(
        () => normalizeServerUrl('http://host?a=1'),
        throwsA(isA<ServerUrlException>()),
      );
      expect(
        () => normalizeServerUrl('http://host#x'),
        throwsA(isA<ServerUrlException>()),
      );
    });
  });

  group('webSocketUrlFor', () {
    test('http -> ws, https -> wss, thêm /ws/websocket', () {
      expect(
        webSocketUrlFor('http://10.0.2.2:8080'),
        equals('ws://10.0.2.2:8080/ws/websocket'),
      );
      expect(
        webSocketUrlFor('https://api.example.com'),
        equals('wss://api.example.com/ws/websocket'),
      );
    });

    test('giữ đường dẫn con của máy chủ', () {
      expect(
        webSocketUrlFor('http://host:8080/qr'),
        equals('ws://host:8080/qr/ws/websocket'),
      );
    });
  });
}
