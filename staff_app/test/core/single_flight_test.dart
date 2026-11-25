import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:staff_app/core/network/single_flight.dart';

void main() {
  group('SingleFlight', () {
    test('nhiều lời gọi đồng thời chỉ chạy tác vụ một lần', () async {
      final flight = SingleFlight<int>();
      var runs = 0;
      final gate = Completer<void>();

      Future<int> task() async {
        runs++;
        await gate.future;
        return 42;
      }

      final calls = List<Future<int>>.generate(10, (_) => flight.run(task));
      expect(flight.isRunning, isTrue);
      gate.complete();
      final results = await Future.wait(calls);

      expect(runs, equals(1));
      expect(results.every((value) => value == 42), isTrue);
      expect(flight.isRunning, isFalse);
    });

    test('lỗi được chia sẻ cho mọi bên chờ rồi cho phép chạy lại', () async {
      final flight = SingleFlight<int>();
      var runs = 0;
      final gate = Completer<void>();

      Future<int> failing() async {
        runs++;
        await gate.future;
        throw StateError('boom');
      }

      final first = flight.run(failing);
      final second = flight.run(failing);
      final expectations = <Future<void>>[
        expectLater(first, throwsA(isA<StateError>())),
        expectLater(second, throwsA(isA<StateError>())),
      ];
      gate.complete();
      await Future.wait(expectations);
      expect(runs, equals(1));

      expect(await flight.run(() async => 7), equals(7));
    });
  });
}
