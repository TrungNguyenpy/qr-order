import 'package:flutter/material.dart';

import '../../shared/widgets/state_views.dart';

/// Màn hình giữ chỗ cho tính năng thuộc Đợt 2.
class PlaceholderScreen extends StatelessWidget {
  const PlaceholderScreen({super.key, required this.title, required this.note});

  final String title;
  final String note;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(title)),
      body: EmptyView(
        icon: Icons.construction_rounded,
        title: 'Sắp có trong Đợt 2',
        message: note,
      ),
    );
  }
}
