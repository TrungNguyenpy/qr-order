import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:staff_app/core/network/api_exception.dart';
import 'package:staff_app/core/providers/core_providers.dart';
import 'package:staff_app/features/tables/application/floor_plan_controller.dart';
import 'package:staff_app/features/tables/domain/table_models.dart';
import 'package:staff_app/features/tables/presentation/floor_plan_screen.dart';
import 'package:staff_app/shared/widgets/state_views.dart';

import '../support/fakes.dart';

class _FakePlan extends FloorPlanController {
  _FakePlan(this._loader);

  final Future<List<FloorPlanTable>> Function() _loader;

  @override
  Future<List<FloorPlanTable>> build() => _loader();

  @override
  Future<void> reload() async {}
}

void main() {
  late FakeTablesRepository tablesRepository;

  Future<void> pumpScreen(
    WidgetTester tester,
    Future<List<FloorPlanTable>> Function() loader,
  ) async {
    tablesRepository = FakeTablesRepository();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          floorPlanProvider.overrideWith(() => _FakePlan(loader)),
          tablesRepositoryProvider.overrideWithValue(tablesRepository),
        ],
        child: const MaterialApp(home: FloorPlanScreen()),
      ),
    );
  }

  final sample = <FloorPlanTable>[
    fakeFloorTable(1),
    fakeFloorTable(2, status: TableStatus.occupied),
    fakeFloorTable(3, status: TableStatus.cleaning, version: 7, area: 'Tầng 2'),
  ];

  testWidgets('Loading: hiện skeleton', (tester) async {
    final never = Completer<List<FloorPlanTable>>();
    await pumpScreen(tester, () => never.future);
    await tester.pump();
    expect(find.byType(SkeletonBox), findsWidgets);
  });

  testWidgets('Success: hiện bàn theo khu vực và số lượng trên bộ lọc', (
    tester,
  ) async {
    await pumpScreen(tester, () async => sample);
    await tester.pump();
    await tester.pump();

    expect(find.text('B01'), findsOneWidget);
    expect(find.text('B02'), findsOneWidget);
    expect(find.text('B03'), findsOneWidget);
    expect(find.text('Tất cả 3'), findsOneWidget);
    expect(find.text('Trống 1'), findsOneWidget);
    expect(find.textContaining('Tầng 2'), findsWidgets);
  });

  testWidgets('chọn bộ lọc "Đang dọn" chỉ còn bàn đang dọn', (tester) async {
    tester.view.physicalSize = const Size(1200, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await pumpScreen(tester, () async => sample);
    await tester.pump();
    await tester.pump();

    await tester.tap(find.text('Đang dọn 1'));
    await tester.pump();
    await tester.pump();

    expect(find.text('B03'), findsOneWidget);
    expect(find.text('B01'), findsNothing);
    expect(find.text('B02'), findsNothing);
  });

  testWidgets('Empty: chưa có bàn nào', (tester) async {
    await pumpScreen(tester, () async => <FloorPlanTable>[]);
    await tester.pump();
    await tester.pump();
    expect(find.text('Chưa có bàn nào'), findsOneWidget);
  });

  testWidgets('Error: hiện lỗi và nút Thử lại gọi tải lại', (tester) async {
    var calls = 0;
    await pumpScreen(tester, () async {
      calls++;
      throw ApiException.network();
    });
    await tester.pump();
    await tester.pump();

    expect(find.text('Không tải được dữ liệu'), findsOneWidget);
    expect(find.text('Thử lại'), findsOneWidget);

    await tester.tap(find.text('Thử lại'));
    await tester.pump();
    await tester.pump();
    expect(calls, equals(2));
  });

  testWidgets('bàn đang dọn: bấm Xác nhận dọn xong gửi đúng id và version', (
    tester,
  ) async {
    await pumpScreen(tester, () async => sample);
    await tester.pump();
    await tester.pump();

    await tester.tap(find.text('B03'));
    await tester.pumpAndSettle();

    expect(find.text('Xác nhận dọn xong'), findsOneWidget);
    await tester.tap(find.text('Xác nhận dọn xong'));
    await tester.pumpAndSettle();

    expect(
      tablesRepository.cleanedCalls,
      equals(<List<int>>[
        <int>[3, 7],
      ]),
    );
  });

  testWidgets('xung đột phiên bản: hiện thông báo và giữ nguyên màn hình', (
    tester,
  ) async {
    await pumpScreen(tester, () async => sample);
    await tester.pump();
    await tester.pump();
    tablesRepository.cleanError = const ApiException(
      code: ApiErrorCodes.concurrentModification,
      message: 'Dữ liệu vừa được người khác cập nhật',
      statusCode: 409,
    );

    await tester.tap(find.text('B03'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Xác nhận dọn xong'));
    await tester.pumpAndSettle();

    expect(find.text('Dữ liệu vừa được người khác cập nhật'), findsOneWidget);
  });
}
