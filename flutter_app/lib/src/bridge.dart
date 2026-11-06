import 'dart:convert';
import 'package:flutter/services.dart';

class Bridge {
  static const MethodChannel _ch = MethodChannel('lxdl/methods');
  static void Function(String type, Map<String, dynamic> data)? onEvent;

  static Future<dynamic> call(String method, [Map<String, dynamic>? args]) {
    return _ch.invokeMethod(method, args);
  }

  static void listen() {
    _ch.setMethodCallHandler((call) async {
      if (call.method == 'event') {
        final map = jsonDecode(call.arguments as String) as Map<String, dynamic>;
        onEvent?.call(map['type'] as String? ?? '', map);
      }
      return null;
    });
  }

  /// 去掉 PlatformException(lxdl, …, null, null) 这层壳，只留人话。
  static String err(Object e) {
    if (e is PlatformException) {
      final m = e.message?.trim();
      if (m != null && m.isNotEmpty) return m;
      return e.code;
    }
    return '$e';
  }
}
