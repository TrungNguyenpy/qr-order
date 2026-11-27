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

  /// Đóng cưỡng bức phiên: `POST /api/v1/sessions/{id}/force-close {reason, version?}`
  /// (cần `SESSION_FORCE_CLOSE`, lý do bắt buộc). Backend tự quyết định kết quả:
  /// ghi nhận thất thu (WRITTEN_OFF) khi còn món tính tiền chưa thu đủ, VOIDED khi
  /// không có món tính tiền, giữ PAID khi Bill đã thu đủ; bàn chuyển Đang dọn.
  /// Từ chối `BILL_VOID_PENDING` / `PENDING_PAYMENT_EXISTS` (xem [ApiException.code]).
  Future<StaffSession> forceClose(
    int sessionId, {
    required String reason,
    int? version,
  }) async {
    final data = await _client.post(
      ApiPaths.sessionForceClose(sessionId),
      data: <String, dynamic>{
        'reason': reason.trim(),
        if (version != null) 'version': version,
      },
    );
    try {
      return StaffSession.fromJson(asMap(data));
    } on FormatException {
      throw ApiException.unexpected();
    }
  }

  /// Phiên gần nhất của bàn đã kết thúc bình thường (CLOSED) hoặc bị đóng cưỡng
  /// bức (FORCE_CLOSED); null khi bàn chưa có phiên nào như vậy.
  /// `GET /api/v1/sessions?tableId=&status=CLOSED,FORCE_CLOSED&size=1` (mới mở trước).
  Future<StaffSession?> latestEndedSession(int tableId) async {
    final data = await _client.get(
      ApiPaths.sessions,
      query: <String, dynamic>{
        'tableId': tableId,
        'status': 'CLOSED,FORCE_CLOSED',
        'page': 0,
        'size': 1,
      },
    );
    try {
      final items = asMapList(asMap(data)['items']);
      return items.isEmpty ? null : StaffSession.fromJson(items.first);
    } on FormatException {
      throw ApiException.unexpected();
    }
  }
}
