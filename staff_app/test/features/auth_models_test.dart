import 'package:flutter_test/flutter_test.dart';
import 'package:staff_app/features/auth/domain/auth_models.dart';
import 'package:staff_app/features/shell/nav_access.dart';

Map<String, dynamic> _userJson({
  String role = 'STAFF',
  List<String> permissions = const <String>[],
}) => <String, dynamic>{
  'id': 5,
  'username': 'staff1',
  'fullName': 'Nguyễn Văn A',
  'role': role,
  'status': 'ACTIVE',
  'permissions': permissions,
};

StaffUser _user(String role, [List<String> permissions = const <String>[]]) =>
    StaffUser.fromJson(_userJson(role: role, permissions: permissions));

void main() {
  group('StaffUser', () {
    test('đọc đầy đủ và bỏ qua permission lạ', () {
      final user = _user('STAFF', <String>['PAYMENT_PROCESS', 'KHONG_TON_TAI']);
      expect(user.id, equals(5));
      expect(user.role, equals(UserRole.staff));
      expect(user.can(Permission.paymentProcess), isTrue);
      expect(user.can(Permission.billVoid), isFalse);
      expect(user.permissions, hasLength(1));
      expect(user.displayName, equals('Nguyễn Văn A'));
    });

    test('ném FormatException khi role không hỗ trợ hoặc thiếu id', () {
      expect(
        () => StaffUser.fromJson(_userJson(role: 'GUEST')),
        throwsA(isA<FormatException>()),
      );
      expect(
        () => StaffUser.fromJson(<String, dynamic>{'role': 'STAFF'}),
        throwsA(isA<FormatException>()),
      );
    });

    test('ADMIN không dùng Staff App', () {
      expect(_user('ADMIN').canUseStaffApp, isFalse);
      expect(_user('STAFF').canUseStaffApp, isTrue);
      expect(_user('KITCHEN').canUseStaffApp, isTrue);
      expect(_user('MANAGER').canUseStaffApp, isTrue);
    });
  });

  group('LoginResult', () {
    test('đọc token và người dùng', () {
      final result = LoginResult.fromJson(<String, dynamic>{
        'accessToken': 'a',
        'refreshToken': 'r',
        'user': _userJson(),
      });
      expect(result.tokens.accessToken, equals('a'));
      expect(result.user.username, equals('staff1'));
    });

    test('thiếu token là lỗi định dạng', () {
      expect(
        () => LoginResult.fromJson(<String, dynamic>{'user': _userJson()}),
        throwsA(isA<FormatException>()),
      );
    });
  });

  group('nav_access', () {
    List<AppTab> tabs(StaffUser user) => tabsFor(user);

    test(
      'STAFF thấy bàn, đơn, tài khoản; thu ngân chỉ khi có PAYMENT_PROCESS',
      () {
        expect(
          tabs(_user('STAFF')),
          equals(<AppTab>[AppTab.tables, AppTab.orders, AppTab.account]),
        );
        expect(
          tabs(_user('STAFF', <String>['PAYMENT_PROCESS'])),
          equals(<AppTab>[
            AppTab.tables,
            AppTab.orders,
            AppTab.cashier,
            AppTab.account,
          ]),
        );
      },
    );

    test('KITCHEN chỉ thấy bếp và tài khoản (không vào /tables)', () {
      final kitchen = _user('KITCHEN');
      expect(tabs(kitchen), equals(<AppTab>[AppTab.kitchen, AppTab.account]));
      expect(canAccessPath('/tables', kitchen), isFalse);
      expect(homePathFor(kitchen), equals('/kitchen'));
    });

    test('MANAGER thấy tất cả', () {
      expect(tabs(_user('MANAGER')), equals(AppTab.values));
    });

    test(
      'ADMIN không có tab nào, chỉ có đường dẫn con hợp lệ cho vai trò khác',
      () {
        final admin = _user('ADMIN');
        expect(canAccessPath('/tables', admin), isFalse);
        expect(canAccessPath('/account', admin), isFalse);
      },
    );

    test('canAccessPath hiểu đường dẫn con và từ chối đường dẫn lạ', () {
      final staff = _user('STAFF');
      expect(canAccessPath('/tables', staff), isTrue);
      expect(canAccessPath('/tables/12', staff), isTrue);
      expect(canAccessPath('/kitchen', staff), isFalse);
      expect(canAccessPath('/unknown', staff), isFalse);
    });
  });
}
