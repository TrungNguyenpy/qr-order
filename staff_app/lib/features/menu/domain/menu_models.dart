import '../../../core/util/json_utils.dart';

/// Danh mục món (backend `CategoryResponse`).
class MenuCategory {
  const MenuCategory({
    required this.id,
    required this.name,
    required this.displayOrder,
    required this.visible,
  });

  factory MenuCategory.fromJson(Map<String, dynamic> json) {
    final id = asIntOrNull(json['id']);
    if (id == null) throw const FormatException('Danh mục không hợp lệ.');
    return MenuCategory(
      id: id,
      name: asString(json['name']),
      displayOrder: asInt(json['displayOrder']),
      visible: asBool(json['visible']),
    );
  }

  final int id;
  final String name;
  final int displayOrder;
  final bool visible;
}

/// Nhóm option đã gán cho món (chỉ thông tin nhóm, chưa có các lựa chọn).
class AssignedOptionGroup {
  const AssignedOptionGroup({
    required this.optionGroupId,
    required this.name,
    required this.active,
  });

  factory AssignedOptionGroup.fromJson(Map<String, dynamic> json) =>
      AssignedOptionGroup(
        optionGroupId: asInt(json['optionGroupId']),
        name: asString(json['name']),
        active: asBool(json['active']),
      );

  final int optionGroupId;
  final String name;
  final bool active;
}

/// Món trong thực đơn (backend `ProductResponse`). [optionGroups] chỉ có ở API chi tiết.
class MenuProduct {
  const MenuProduct({
    required this.id,
    required this.categoryId,
    required this.name,
    required this.price,
    required this.available,
    this.imageUrl,
    this.optionGroups,
  });

  factory MenuProduct.fromJson(Map<String, dynamic> json) {
    final id = asIntOrNull(json['id']);
    if (id == null) throw const FormatException('Món không hợp lệ.');
    final groups = json['optionGroups'];
    return MenuProduct(
      id: id,
      categoryId: asInt(json['categoryId']),
      name: asString(json['name']),
      price: asInt(json['price']),
      available: asString(json['status']) == 'AVAILABLE',
      imageUrl: asStringOrNull(json['imageUrl']),
      optionGroups: groups is List
          ? asMapList(groups)
                .map(AssignedOptionGroup.fromJson)
                .where((g) => g.active)
                .toList()
          : null,
    );
  }

  final int id;
  final int categoryId;
  final String name;
  final int price;
  final bool available;
  final String? imageUrl;
  final List<AssignedOptionGroup>? optionGroups;
}

/// Một lựa chọn trong nhóm option.
class OptionChoice {
  const OptionChoice({
    required this.id,
    required this.name,
    required this.extraPrice,
  });

  final int id;
  final String name;
  final int extraPrice;
}

/// Nhóm option đầy đủ (backend `OptionGroupResponse`), chỉ giữ lựa chọn đang bật.
class OptionGroupDetail {
  const OptionGroupDetail({
    required this.id,
    required this.name,
    required this.multiple,
    required this.required,
    required this.minSelect,
    required this.maxSelect,
    required this.items,
  });

  factory OptionGroupDetail.fromJson(Map<String, dynamic> json) {
    final id = asIntOrNull(json['id']);
    if (id == null) throw const FormatException('Nhóm option không hợp lệ.');
    return OptionGroupDetail(
      id: id,
      name: asString(json['name']),
      multiple: asString(json['selectionType']) == 'MULTIPLE',
      required: asBool(json['required']),
      minSelect: asInt(json['minSelect']),
      maxSelect: asIntOrNull(json['maxSelect']),
      items: asMapList(json['items'])
          .where((e) => asBool(e['active']))
          .map(
            (e) => OptionChoice(
              id: asInt(e['id']),
              name: asString(e['name']),
              extraPrice: asInt(e['extraPrice']),
            ),
          )
          .toList(),
    );
  }

  final int id;
  final String name;
  final bool multiple;
  final bool required;
  final int minSelect;
  final int? maxSelect;
  final List<OptionChoice> items;

  /// Số lựa chọn tối thiểu thực sự phải chọn (nhóm bắt buộc tối thiểu 1).
  int get effectiveMin => required && minSelect < 1 ? 1 : minSelect;

  /// Kiểm tra số lựa chọn [selectedCount] của nhóm; null nếu hợp lệ.
  String? validateCount(int selectedCount) {
    if (selectedCount < effectiveMin) {
      return effectiveMin <= 1
          ? 'Chọn "$name"'
          : 'Chọn ít nhất $effectiveMin cho "$name"';
    }
    final max = multiple ? maxSelect : 1;
    if (max != null && selectedCount > max) {
      return 'Chọn tối đa $max cho "$name"';
    }
    return null;
  }
}

/// Một dòng trong giỏ gọi món hộ.
class CartLine {
  const CartLine({
    required this.product,
    required this.quantity,
    this.note,
    this.choices = const <OptionChoice>[],
  });

  final MenuProduct product;
  final int quantity;
  final String? note;
  final List<OptionChoice> choices;

  /// Đơn giá gồm phụ thu option.
  int get unitPrice =>
      product.price + choices.fold<int>(0, (sum, c) => sum + c.extraPrice);

  int get lineTotal => unitPrice * quantity;

  /// Hai dòng giống hệt (cùng món, option, ghi chú) thì gộp số lượng.
  bool sameAs(CartLine other) {
    if (product.id != other.product.id) return false;
    if ((note ?? '').trim() != (other.note ?? '').trim()) return false;
    final a = choices.map((c) => c.id).toList()..sort();
    final b = other.choices.map((c) => c.id).toList()..sort();
    if (a.length != b.length) return false;
    for (var i = 0; i < a.length; i++) {
      if (a[i] != b[i]) return false;
    }
    return true;
  }

  CartLine withQuantity(int value) =>
      CartLine(product: product, quantity: value, note: note, choices: choices);

  Map<String, dynamic> toJson() => <String, dynamic>{
    'productId': product.id,
    'quantity': quantity,
    if ((note ?? '').trim().isNotEmpty) 'note': note!.trim(),
    'optionItemIds': choices.map((c) => c.id).toList(),
  };
}

/// Thêm [line] vào giỏ: gộp nếu đã có dòng giống hệt, trả giỏ mới.
List<CartLine> addToCart(List<CartLine> cart, CartLine line) {
  final index = cart.indexWhere(line.sameAs);
  if (index < 0) return <CartLine>[...cart, line];
  final merged = cart[index].withQuantity(cart[index].quantity + line.quantity);
  return <CartLine>[...cart]..[index] = merged;
}

int cartTotal(List<CartLine> cart) =>
    cart.fold<int>(0, (sum, line) => sum + line.lineTotal);
