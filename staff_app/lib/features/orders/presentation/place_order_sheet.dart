import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../app/theme.dart';
import '../../../core/providers/core_providers.dart';
import '../../../core/util/format.dart';
import '../../../core/util/uuid.dart';
import '../../../shared/ui_actions.dart';
import '../../../shared/widgets/state_views.dart';
import '../../menu/application/menu_providers.dart';
import '../../menu/domain/menu_models.dart';
import '../application/session_order_provider.dart';
import '../data/orders_repository.dart';
import 'option_picker_dialog.dart';

/// Nhân viên gọi món hộ khách: chọn món (kèm option), giỏ, gửi một đợt.
/// Đợt do nhân viên tạo vào thẳng hàng chờ xác nhận như đợt của khách.
Future<void> showPlaceOrderSheet(
  BuildContext context, {
  required int sessionId,
  required String tableCode,
}) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    constraints: const BoxConstraints(maxWidth: 760),
    builder: (_) => SizedBox(
      height: MediaQuery.sizeOf(context).height * 0.92,
      child: PlaceOrderView(sessionId: sessionId, tableCode: tableCode),
    ),
  );
}

class PlaceOrderView extends ConsumerStatefulWidget {
  const PlaceOrderView({
    super.key,
    required this.sessionId,
    required this.tableCode,
  });

  final int sessionId;
  final String tableCode;

  @override
  ConsumerState<PlaceOrderView> createState() => _PlaceOrderViewState();
}

class _PlaceOrderViewState extends ConsumerState<PlaceOrderView> {
  List<CartLine> _cart = const <CartLine>[];
  int? _categoryId;
  String _query = '';
  bool _submitting = false;

  /// Khóa idempotent của đợt. Giữ nguyên khi thử lại cùng giỏ sau lỗi mạng;
  /// đổi khi giỏ thay đổi để không "phát lại" một đợt khác nội dung.
  String _requestId = generateUuidV4();

  void _setCart(List<CartLine> cart) {
    setState(() {
      _cart = cart;
      _requestId = generateUuidV4();
    });
  }

  Future<void> _add(MenuProduct product) async {
    // Món có option phải chọn đủ nhóm bắt buộc trước khi vào giỏ.
    final detail = await runGuardedValue<MenuProduct>(
      context,
      () => ref.read(menuRepositoryProvider).product(product.id),
    );
    if (detail == null || !mounted) return;
    final groups = await runGuardedValue<List<OptionGroupDetail>>(
      context,
      () => ref.read(menuRepositoryProvider).optionGroupsOf(detail),
    );
    if (groups == null || !mounted) return;
    final line = groups.isEmpty
        ? CartLine(product: product, quantity: 1)
        : await showOptionPickerDialog(
            context,
            product: product,
            groups: groups,
          );
    if (line == null) return;
    _setCart(addToCart(_cart, line));
  }

  void _changeQuantity(int index, int delta) {
    final next = _cart[index].quantity + delta;
    final cart = <CartLine>[..._cart];
    if (next < 1) {
      cart.removeAt(index);
    } else {
      cart[index] = cart[index].withQuantity(next);
    }
    _setCart(cart);
  }

  Future<void> _submit() async {
    setState(() => _submitting = true);
    final placed = await runGuardedValue<PlacedBatch>(
      context,
      () => ref
          .read(ordersRepositoryProvider)
          .placeForGuest(
            widget.sessionId,
            clientRequestId: _requestId,
            items: _cart.map((l) => l.toJson()).toList(),
          ),
    );
    if (!mounted) return;
    setState(() => _submitting = false);
    if (placed == null) return;
    ref.invalidate(sessionOrderProvider(widget.sessionId));
    final replayed = placed.replayed;
    Navigator.of(context).pop();
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          replayed
              ? 'Đợt này đã được gửi trước đó.'
              : 'Đã gửi đợt gọi món cho bàn ${widget.tableCode}.',
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final menu = ref.watch(menuCatalogProvider);
    return Padding(
      padding: const EdgeInsets.fromLTRB(
        AppSpacing.lg,
        0,
        AppSpacing.lg,
        AppSpacing.lg,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: <Widget>[
          Text(
            'Gọi món hộ · ${widget.tableCode}',
            style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: AppSpacing.md),
          Expanded(
            flex: 3,
            child: menu.when(
              loading: () => const _MenuSkeleton(),
              error: (error, _) => ErrorView(
                error: error,
                title: 'Không tải được thực đơn',
                onRetry: () => ref.invalidate(menuCatalogProvider),
              ),
              data: (catalog) => _MenuPicker(
                catalog: catalog,
                categoryId: _categoryId,
                query: _query,
                onCategory: (id) => setState(() => _categoryId = id),
                onQuery: (value) => setState(() => _query = value),
                onPick: _add,
              ),
            ),
          ),
          const Divider(height: AppSpacing.xl),
          Expanded(
            flex: 2,
            child: _cart.isEmpty
                ? const EmptyView(
                    icon: Icons.shopping_basket_outlined,
                    title: 'Chưa chọn món',
                    message: 'Chạm vào món ở trên để thêm vào đợt.',
                  )
                : ListView.separated(
                    itemCount: _cart.length,
                    separatorBuilder: (_, __) => const Divider(height: 1),
                    itemBuilder: (context, index) => _CartRow(
                      line: _cart[index],
                      onMinus: () => _changeQuantity(index, -1),
                      onPlus: () => _changeQuantity(index, 1),
                    ),
                  ),
          ),
          const SizedBox(height: AppSpacing.sm),
          Row(
            children: <Widget>[
              Text(
                'Tạm tính ${formatVnd(cartTotal(_cart))}',
                style: const TextStyle(fontWeight: FontWeight.w700),
              ),
              const Spacer(),
              FilledButton(
                onPressed: _cart.isEmpty || _submitting ? null : _submit,
                child: _submitting
                    ? const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: Colors.white,
                        ),
                      )
                    : Text(
                        'Gửi đợt (${_cart.fold<int>(0, (s, l) => s + l.quantity)} món)',
                      ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _MenuSkeleton extends StatelessWidget {
  const _MenuSkeleton();

  @override
  Widget build(BuildContext context) {
    return ListView(
      physics: const NeverScrollableScrollPhysics(),
      children: const <Widget>[
        SkeletonBox(height: 36),
        SizedBox(height: 12),
        SkeletonBox(height: 48),
        SizedBox(height: 8),
        SkeletonBox(height: 48),
        SizedBox(height: 8),
        SkeletonBox(height: 48),
      ],
    );
  }
}

class _MenuPicker extends StatelessWidget {
  const _MenuPicker({
    required this.catalog,
    required this.categoryId,
    required this.query,
    required this.onCategory,
    required this.onQuery,
    required this.onPick,
  });

  final MenuCatalog catalog;
  final int? categoryId;
  final String query;
  final ValueChanged<int?> onCategory;
  final ValueChanged<String> onQuery;
  final ValueChanged<MenuProduct> onPick;

  @override
  Widget build(BuildContext context) {
    final products = catalog.filter(categoryId: categoryId, query: query);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: <Widget>[
        TextField(
          onChanged: onQuery,
          decoration: const InputDecoration(
            prefixIcon: Icon(Icons.search_rounded),
            hintText: 'Tìm món',
            isDense: true,
          ),
        ),
        const SizedBox(height: AppSpacing.sm),
        SizedBox(
          height: 40,
          child: ListView(
            scrollDirection: Axis.horizontal,
            children: <Widget>[
              Padding(
                padding: const EdgeInsets.only(right: AppSpacing.sm),
                child: ChoiceChip(
                  label: const Text('Tất cả'),
                  selected: categoryId == null,
                  onSelected: (_) => onCategory(null),
                ),
              ),
              for (final category in catalog.categories)
                Padding(
                  padding: const EdgeInsets.only(right: AppSpacing.sm),
                  child: ChoiceChip(
                    label: Text(category.name),
                    selected: categoryId == category.id,
                    onSelected: (_) => onCategory(category.id),
                  ),
                ),
            ],
          ),
        ),
        const SizedBox(height: AppSpacing.sm),
        Expanded(
          child: products.isEmpty
              ? const EmptyView(
                  icon: Icons.search_off_rounded,
                  title: 'Không có món phù hợp',
                  message: 'Thử đổi danh mục hoặc từ khóa.',
                )
              : ListView.separated(
                  itemCount: products.length,
                  separatorBuilder: (_, __) => const Divider(height: 1),
                  itemBuilder: (context, index) {
                    final product = products[index];
                    return ListTile(
                      dense: true,
                      title: Text(product.name),
                      subtitle: Text(formatVnd(product.price)),
                      trailing: const Icon(Icons.add_circle_outline_rounded),
                      onTap: () => onPick(product),
                    );
                  },
                ),
        ),
      ],
    );
  }
}

class _CartRow extends StatelessWidget {
  const _CartRow({
    required this.line,
    required this.onMinus,
    required this.onPlus,
  });

  final CartLine line;
  final VoidCallback onMinus;
  final VoidCallback onPlus;

  @override
  Widget build(BuildContext context) {
    final detail = <String>[
      ...line.choices.map((c) => c.name),
      if ((line.note ?? '').trim().isNotEmpty) 'Ghi chú: ${line.note!.trim()}',
    ].join(' · ');
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        children: <Widget>[
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Text(
                  line.product.name,
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
                if (detail.isNotEmpty)
                  Text(
                    detail,
                    style: const TextStyle(
                      color: AppColors.muted,
                      fontSize: 12,
                    ),
                  ),
              ],
            ),
          ),
          IconButton(
            onPressed: onMinus,
            icon: const Icon(Icons.remove_circle_outline_rounded),
            tooltip: 'Giảm',
          ),
          Text(
            '${line.quantity}',
            style: const TextStyle(fontWeight: FontWeight.w700),
          ),
          IconButton(
            onPressed: onPlus,
            icon: const Icon(Icons.add_circle_outline_rounded),
            tooltip: 'Tăng',
          ),
          SizedBox(
            width: 84,
            child: Text(formatVnd(line.lineTotal), textAlign: TextAlign.right),
          ),
        ],
      ),
    );
  }
}
