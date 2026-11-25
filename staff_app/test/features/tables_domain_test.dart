import 'package:flutter_test/flutter_test.dart';
import 'package:staff_app/core/realtime/realtime_message.dart';
import 'package:staff_app/features/auth/domain/auth_models.dart';
import 'package:staff_app/features/tables/domain/table_filters.dart';
import 'package:staff_app/features/tables/domain/table_models.dart';

FloorPlanTable _table(
  int id, {
  String code = 'B01',
  String name = 'Bàn 1',
  String? area = 'Tầng 1',
  TableStatus status = TableStatus.available,
  int version = 1,
  bool withSession = false,
}) {
  return FloorPlanTable.fromJson(<String, dynamic>{
    'table': <String, dynamic>{
      'id': id,
      'code': code,
      'displayName': name,
      'area': area,
      'capacity': 4,
      'status': status.apiName,
      'active': true,
      'displayOrder': id,
      'version': version,
    },
    'hasActiveQr': true,
    'activeSession': withSession
        ? <String, dynamic>{
            'id': 100 + id,
            'status': 'OPEN',
            'lastActivityAt': '2026-11-24T10:00:00Z',
            'needsAttention': true,
            'deviceCount': 2,
            'version': 3,
          }
        : null,
  });
}

RealtimeMessage _event({
  required int tableId,
  required String status,
  required int version,
  String type = 'TABLE_CHANGED',
}) {
  return RealtimeMessage(
    destination: StompTopics.tables,
    payload: <String, dynamic>{
      'type': type,
      'eventId': 9,
      'tableId': tableId,
      'status': status,
      'active': true,
      'version': version,
      'change': 'STATUS',
    },
  );
}

void main() {
  group('FloorPlanTable.fromJson', () {
    test('đọc bàn có và không có phiên', () {
      final busy = _table(1, status: TableStatus.occupied, withSession: true);
      final free = _table(2);
      expect(busy.activeSession, isNotNull);
      expect(busy.activeSession!.needsAttention, isTrue);
      expect(free.activeSession, isNull);
    });

    test('trạng thái bàn lạ là lỗi định dạng', () {
      expect(
        () => DiningTable.fromJson(<String, dynamic>{
          'id': 1,
          'status': 'FLYING',
        }),
        throwsA(isA<FormatException>()),
      );
    });
  });

  group('lọc, tìm kiếm, nhóm', () {
    final tables = <FloorPlanTable>[
      _table(1, code: 'B01', name: 'Bàn cửa sổ', area: 'Tầng 1'),
      _table(
        2,
        code: 'B02',
        name: 'Bàn góc',
        area: 'Tầng 2',
        status: TableStatus.occupied,
        withSession: true,
      ),
      _table(
        3,
        code: 'V01',
        name: 'Phòng VIP',
        area: null,
        status: TableStatus.cleaning,
      ),
      _table(
        4,
        code: 'B04',
        name: 'Bàn ngoài',
        area: 'Tầng 1',
        status: TableStatus.paymentPending,
      ),
    ];

    test('lọc theo trạng thái', () {
      final result = filterTables(tables, filter: TableFilter.occupied);
      expect(result.map((t) => t.id).toList(), equals(<int>[2]));
    });

    test('tìm kiếm không phân biệt dấu và hoa thường', () {
      expect(foldVietnamese('Đường Phố Ế'), equals('duong pho e'));
      final byName = filterTables(tables, query: 'cua so');
      expect(byName.map((t) => t.id).toList(), equals(<int>[1]));
      final byArea = filterTables(tables, query: 'TANG 2');
      expect(byArea.map((t) => t.id).toList(), equals(<int>[2]));
    });

    test('đếm theo từng bộ lọc', () {
      final counts = countByFilter(tables);
      expect(counts[TableFilter.all], equals(4));
      expect(counts[TableFilter.available], equals(1));
      expect(counts[TableFilter.cleaning], equals(1));
      expect(counts[TableFilter.paymentPending], equals(1));
    });

    test(
      'nhóm theo khu vực giữ thứ tự xuất hiện, bàn chưa gán khu vực riêng',
      () {
        final groups = groupByArea(tables);
        expect(
          groups.map((g) => g.area).toList(),
          equals(<String?>['Tầng 1', 'Tầng 2', null]),
        );
        expect(groups.first.tables, hasLength(2));
      },
    );
  });

  group('sự kiện /topic/tables', () {
    test('tryParse nhận TABLE_CHANGED hợp lệ và bỏ qua loại khác', () {
      expect(
        TableChangedEvent.tryParse(
          _event(tableId: 1, status: 'OCCUPIED', version: 2),
        ),
        isNotNull,
      );
      expect(
        TableChangedEvent.tryParse(
          _event(tableId: 1, status: 'OCCUPIED', version: 2, type: 'OTHER'),
        ),
        isNull,
      );
      expect(
        TableChangedEvent.tryParse(
          _event(tableId: 1, status: 'LẠ', version: 2),
        ),
        isNull,
      );
    });

    test('applyTableEvent đổi trạng thái khi version mới hơn', () {
      final tables = <FloorPlanTable>[
        _table(1, status: TableStatus.occupied, version: 2, withSession: true),
      ];
      final event = TableChangedEvent.tryParse(
        _event(tableId: 1, status: 'CLEANING', version: 3),
      )!;
      final updated = applyTableEvent(tables, event);
      expect(updated.first.table.status, equals(TableStatus.cleaning));
      expect(updated.first.table.version, equals(3));
      expect(updated.first.activeSession, isNull);
    });

    test('applyTableEvent bỏ qua sự kiện cũ hoặc trùng version', () {
      final tables = <FloorPlanTable>[
        _table(1, status: TableStatus.occupied, version: 5, withSession: true),
      ];
      final stale = TableChangedEvent.tryParse(
        _event(tableId: 1, status: 'AVAILABLE', version: 5),
      )!;
      final updated = applyTableEvent(tables, stale);
      expect(updated.first.table.status, equals(TableStatus.occupied));
      expect(updated.first.activeSession, isNotNull);
    });
  });

  group('realtime_message', () {
    test('tryParse chỉ nhận JSON object', () {
      expect(
        RealtimeMessage.tryParse('/topic/tables', '{"type":"X"}'),
        isNotNull,
      );
      expect(RealtimeMessage.tryParse('/topic/tables', '[1,2]'), isNull);
      expect(
        RealtimeMessage.tryParse('/topic/tables', 'không phải json'),
        isNull,
      );
      expect(RealtimeMessage.tryParse('/topic/tables', null), isNull);
    });

    test('kênh theo vai trò khớp backend', () {
      expect(
        topicsForRole(UserRole.staff),
        equals(<String>[
          StompTopics.tables,
          StompTopics.staffOrders,
          StompTopics.staffReady,
        ]),
      );
      expect(
        topicsForRole(UserRole.kitchen),
        equals(<String>[StompTopics.kitchenOrders]),
      );
      expect(topicsForRole(UserRole.manager), hasLength(4));
      expect(topicsForRole(UserRole.admin), isEmpty);
    });
  });
}
