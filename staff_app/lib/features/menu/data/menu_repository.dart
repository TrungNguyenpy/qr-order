import '../../../core/network/api_client.dart';
import '../../../core/network/api_exception.dart';
import '../../../core/network/api_paths.dart';
import '../../../core/util/json_utils.dart';
import '../domain/menu_models.dart';

/// Đọc thực đơn cho nhân viên gọi món hộ (STAFF/MANAGER có quyền xem).
class MenuRepository {
  MenuRepository(this._client);

  final ApiClient _client;

  static const int _pageSize = 100;

  T _parse<T>(T Function() builder) {
    try {
      return builder();
    } on FormatException {
      throw ApiException.unexpected();
    }
  }

  /// Danh mục đang hiển thị, theo thứ tự hiển thị.
  Future<List<MenuCategory>> categories() async {
    final data = await _client.get(
      ApiPaths.categories,
      query: <String, dynamic>{'visible': true, 'size': _pageSize},
    );
    return _parse(
      () => asMapList(asMap(data)['items']).map(MenuCategory.fromJson).toList(),
    );
  }

  /// Món đang bán (AVAILABLE), duyệt hết các trang.
  Future<List<MenuProduct>> availableProducts() async {
    final result = <MenuProduct>[];
    var page = 0;
    while (true) {
      final data = asMap(
        await _client.get(
          ApiPaths.products,
          query: <String, dynamic>{
            'status': 'AVAILABLE',
            'page': page,
            'size': _pageSize,
          },
        ),
      );
      result.addAll(
        _parse(
          () => asMapList(data['items']).map(MenuProduct.fromJson).toList(),
        ),
      );
      final totalPages = asInt(data['totalPages']);
      page++;
      if (page >= totalPages) break;
    }
    return result;
  }

  /// Chi tiết món kèm các nhóm option đã gán.
  Future<MenuProduct> product(int id) async {
    final data = await _client.get(ApiPaths.product(id));
    return _parse(() => MenuProduct.fromJson(asMap(data)));
  }

  Future<OptionGroupDetail> optionGroup(int id) async {
    final data = await _client.get(ApiPaths.optionGroup(id));
    return _parse(() => OptionGroupDetail.fromJson(asMap(data)));
  }

  /// Các nhóm option (đang bật, đã có lựa chọn) của một món, đúng thứ tự gán.
  Future<List<OptionGroupDetail>> optionGroupsOf(MenuProduct product) async {
    final assigned = product.optionGroups ?? const <AssignedOptionGroup>[];
    final groups = await Future.wait(
      assigned.map((g) => optionGroup(g.optionGroupId)),
    );
    return groups.where((g) => g.items.isNotEmpty).toList();
  }
}
