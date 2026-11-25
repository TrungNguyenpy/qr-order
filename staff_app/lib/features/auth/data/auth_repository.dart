import '../../../core/network/api_client.dart';
import '../../../core/network/api_exception.dart';
import '../../../core/network/api_paths.dart';
import '../../../core/storage/token_store.dart';
import '../../../core/util/json_utils.dart';
import '../domain/auth_models.dart';

/// Gọi API xác thực. Mọi lỗi được ném dưới dạng [ApiException].
class AuthRepository {
  AuthRepository(this._client, this._tokens);

  final ApiClient _client;
  final TokenStore _tokens;

  /// Đăng nhập. Token chỉ được lưu khi vai trò dùng được Staff App; ADMIN bị
  /// từ chối bằng [ClientErrorCodes.roleNotSupported] (dùng Admin Dashboard).
  Future<StaffUser> login(String username, String password) async {
    final data = await _client.request(
      'POST',
      ApiPaths.login,
      data: <String, dynamic>{
        'username': username.trim(),
        'password': password,
      },
      authenticated: false,
    );
    final LoginResult result;
    try {
      result = LoginResult.fromJson(asMap(data));
    } on FormatException {
      throw ApiException.unexpected();
    }
    if (!result.user.canUseStaffApp) {
      // Thu hồi phiên vừa tạo để không để lại refresh token mồ côi.
      await _bestEffortLogout(result.tokens.refreshToken);
      throw const ApiException(
        code: ClientErrorCodes.roleNotSupported,
        message: 'Tài khoản Quản trị viên không dùng Staff App. Hãy dùng Admin Dashboard.',
      );
    }
    await _tokens.save(result.tokens);
    return result.user;
  }

  /// Lấy lại thông tin tài khoản (khôi phục phiên, làm mới quyền).
  Future<StaffUser> me() async {
    final data = await _client.get(ApiPaths.me);
    try {
      return StaffUser.fromJson(asMap(data));
    } on FormatException {
      throw ApiException.unexpected();
    }
  }

  /// Đăng xuất: thu hồi refresh token trên server (best-effort) rồi xóa token cục bộ.
  Future<void> logout() async {
    final refresh = _tokens.current?.refreshToken;
    if (refresh != null) await _bestEffortLogout(refresh);
    await _tokens.clear();
  }

  /// Đổi mật khẩu. Thành công thì backend thu hồi mọi refresh token → phải đăng nhập lại.
  Future<void> changePassword({
    required String currentPassword,
    required String newPassword,
  }) async {
    await _client.post(
      ApiPaths.changePassword,
      data: <String, dynamic>{
        'currentPassword': currentPassword,
        'newPassword': newPassword,
      },
    );
  }

  /// Kiểm tra máy chủ có phản hồi không (nút "Kiểm tra kết nối" ở màn đăng nhập).
  Future<void> ping() async {
    try {
      await _client.request('GET', ApiPaths.health, authenticated: false);
    } on ApiException catch (error) {
      // Actuator trả `{"status":"UP"}` (không có phong bì ApiResponse): HTTP 200
      // nghĩa là máy chủ sống dù thân phản hồi không đúng định dạng chuẩn.
      final reachable =
          error.code == ClientErrorCodes.unexpectedResponse &&
          error.statusCode == 200;
      if (!reachable) rethrow;
    }
  }

  Future<void> _bestEffortLogout(String refreshToken) async {
    try {
      await _client.request(
        'POST',
        ApiPaths.logout,
        data: <String, dynamic>{'refreshToken': refreshToken},
        authenticated: false,
      );
    } on ApiException {
      // Không chặn đăng xuất cục bộ khi mạng lỗi hoặc token đã bị thu hồi.
    }
  }
}
