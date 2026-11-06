import 'bridge.dart';

/// 封面取本地路径的统一入口：原生侧有磁盘缓存，这里再存一层内存映射，
/// 同一次运行里重复用到同一张封面不会再走通道。
class CoverStore {
  static final Map<String, String> _paths = {};

  static Future<String?> path(String? url) async {
    final u = (url ?? '').trim();
    if (u.isEmpty) return null;
    final hit = _paths[u];
    if (hit != null) return hit;
    try {
      final p = await Bridge.call('cover', {'url': u}) as String?;
      if (p == null || p.isEmpty) return null;
      _paths[u] = p;
      return p;
    } catch (_) {
      return null;
    }
  }
}
