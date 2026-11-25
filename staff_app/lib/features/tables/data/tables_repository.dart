import '../../../core/network/api_client.dart';
import '../../../core/network/api_exception.dart';
import '../../../core/network/api_paths.dart';
import '../../../core/util/json_utils.dart';
import '../domain/table_models.dart';

class TablesRepository {
  TablesRepository(this._client);

  final ApiClient _client;

  /// `GET /api/v1/tables/floor-plan` (STAFF, MANAGER).
  Future<List<FloorPlanTable>> floorPlan() async {
    final data = await _client.get(ApiPaths.floorPlan);
    try {
      return asMapList(data).map(FloorPlanTable.fromJson).toList();
    } on FormatException {
      throw ApiException.unexpected();
    }
  }

  /// `GET /api/v1/sessions/{id}` — chi tiết phiên kèm PIN bàn (STAFF, MANAGER).
  Future<StaffSession> session(int sessionId) async {
    final data = await _client.get(ApiPaths.session(sessionId));
    try {
      return StaffSession.fromJson(asMap(data));
    } on FormatException {
      throw ApiException.unexpected();
    }
  }

  /// Xác nhận dọn xong: `PATCH /api/v1/tables/{id}/status {status: AVAILABLE, version}`.
  /// Backend chỉ cho CLEANING → AVAILABLE; version sai → 409 CONCURRENT_MODIFICATION.
  Future<DiningTable> confirmCleaned({
    required int tableId,
    required int version,
  }) async {
    final data = await _client.patch(
      ApiPaths.tableStatus(tableId),
      data: <String, dynamic>{
        'status': TableStatus.available.apiName,
        'version': version,
      },
    );
    try {
      return DiningTable.fromJson(asMap(data));
    } on FormatException {
      throw ApiException.unexpected();
    }
  }
}
