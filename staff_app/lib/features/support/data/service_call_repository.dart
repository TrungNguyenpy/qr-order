import '../../../core/network/api_client.dart';
import '../../../core/network/api_exception.dart';
import '../../../core/network/api_paths.dart';
import '../../../core/util/json_utils.dart';
import '../domain/service_call_models.dart';

/// Hàng đợi yêu cầu hỗ trợ (MANAGER/STAFF).
class ServiceCallRepository {
  ServiceCallRepository(this._client);

  final ApiClient _client;

  T _parse<T>(T Function() builder) {
    try {
      return builder();
    } on FormatException {
      throw ApiException.unexpected();
    }
  }

  /// Mặc định OPEN + ACKNOWLEDGED, cũ trước.
  Future<List<ServiceCall>> queue() async {
    final data = await _client.get(ApiPaths.serviceCalls);
    return _parse(() => asMapList(data).map(ServiceCall.fromJson).toList());
  }

  /// OPEN → ACKNOWLEDGED. MANAGER_SUPPORT chỉ Manager.
  Future<ServiceCall> acknowledge(int id) async {
    final data = await _client.post(
      ApiPaths.serviceCallAction(id, 'acknowledge'),
    );
    return _parse(() => ServiceCall.fromJson(asMap(data)));
  }

  /// → RESOLVED, ghi chú tùy chọn.
  Future<ServiceCall> resolve(int id, {String? note}) async {
    final data = await _client.post(
      ApiPaths.serviceCallAction(id, 'resolve'),
      data: <String, dynamic>{
        if (note != null && note.trim().isNotEmpty) 'note': note.trim(),
      },
    );
    return _parse(() => ServiceCall.fromJson(asMap(data)));
  }

  /// Staff gửi Manager hỗ trợ cho một phiên (`MANAGER_SUPPORT`).
  Future<ServiceCall> requestManager(int sessionId, {String? note}) async {
    final data = await _client.post(
      ApiPaths.serviceCalls,
      data: <String, dynamic>{
        'sessionId': sessionId,
        if (note != null && note.trim().isNotEmpty) 'note': note.trim(),
      },
    );
    return _parse(() => ServiceCall.fromJson(asMap(data)));
  }
}
