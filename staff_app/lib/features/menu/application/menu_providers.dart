import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/providers/core_providers.dart';
import '../domain/menu_models.dart';

/// Thực đơn dùng để gọi món hộ: danh mục hiển thị + món đang bán.
class MenuCatalog {
  const MenuCatalog({required this.categories, required this.products});

  final List<MenuCategory> categories;
  final List<MenuProduct> products;

  /// Món của danh mục [categoryId] (null = tất cả), lọc theo từ khóa [query].
  List<MenuProduct> filter({int? categoryId, String query = ''}) {
    final needle = query.trim().toLowerCase();
    return products
        .where(
          (p) =>
              (categoryId == null || p.categoryId == categoryId) &&
              (needle.isEmpty || p.name.toLowerCase().contains(needle)),
        )
        .toList();
  }
}

final menuCatalogProvider = FutureProvider.autoDispose<MenuCatalog>((
  ref,
) async {
  final repo = ref.watch(menuRepositoryProvider);
  final results = await Future.wait<Object>(<Future<Object>>[
    repo.categories(),
    repo.availableProducts(),
  ]);
  final categories = results[0] as List<MenuCategory>;
  final visibleIds = categories.map((c) => c.id).toSet();
  final products = (results[1] as List<MenuProduct>)
      .where((p) => p.available && visibleIds.contains(p.categoryId))
      .toList();
  return MenuCatalog(categories: categories, products: products);
});
