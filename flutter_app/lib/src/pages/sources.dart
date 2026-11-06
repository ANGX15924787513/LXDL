import 'dart:convert';
import 'package:flutter/material.dart';
import '../bridge.dart';

const _themePresets = <(String, String)>[
  ('蓝', '#3A7AF0'),
  ('青', '#00BFA6'),
  ('绿', '#43A047'),
  ('橙', '#FF8F00'),
  ('红', '#E53935'),
  ('粉', '#EC407A'),
  ('紫', '#8E24AA'),
  ('灰', '#546E7A'),
];

const _defaultThemeHex = '#3A7AF0';

class SourcesPage extends StatefulWidget {
  const SourcesPage({super.key, required this.themeSeed});

  /// 改主题色时立刻生效，同时写回原生配置
  final ValueNotifier<Color> themeSeed;

  @override
  State<SourcesPage> createState() => _SourcesPageState();
}

class _SourcesPageState extends State<SourcesPage> {
  List<Map<String, dynamic>> _items = [];
  final _mirror = TextEditingController();
  final _proxy = TextEditingController();
  String _status = '';
  bool _importing = false;
  bool _probing = false;
  Map<String, Map<String, dynamic>> _health = {};
  int _concurrency = 1;
  bool _wifiOnly = true;
  bool _lrcSidecar = false;
  String _outTree = '';
  String _theme = _defaultThemeHex;

  @override
  void initState() {
    super.initState();
    _load();
    _loadConfig();
  }

  Future<void> _loadConfig() async {
    try {
      final raw = await Bridge.call('config') as String?;
      final cfg = jsonDecode(raw ?? '{}') as Map<String, dynamic>;
      if (!mounted) return;
      setState(() {
        _mirror.text = cfg['mirror'] as String? ?? '';
        _proxy.text = cfg['proxy'] as String? ?? '';
        _concurrency = (cfg['concurrency'] as int?) ?? 1;
        _wifiOnly = cfg['wifiOnly'] as bool? ?? true;
        _lrcSidecar = cfg['lrcSidecar'] as bool? ?? false;
        _outTree = cfg['outTree'] as String? ?? '';
        final t = (cfg['theme'] as String?)?.trim() ?? '';
        if (t.isNotEmpty) _theme = t.toUpperCase();
      });
    } catch (_) {}
  }

  Future<void> _load() async {
    try {
      final raw = await Bridge.call('sources') as String?;
      final list = (jsonDecode(raw ?? '[]') as List).cast<Map<String, dynamic>>();
      if (!mounted) return;
      setState(() {
        _items = list;
        _status = '共 ${list.length} 个音源';
      });
    } catch (e) {
      if (mounted) setState(() => _status = Bridge.err(e));
    }
  }

  Future<void> _move(String name, int delta) async {
    try {
      final raw = await Bridge.call('moveSource', {'name': name, 'delta': delta}) as String?;
      final list = (jsonDecode(raw ?? '[]') as List).cast<Map<String, dynamic>>();
      setState(() => _items = list);
    } catch (_) {}
  }

  Future<void> _delete(String name) async {
    try {
      final raw = await Bridge.call('deleteSource', {'name': name}) as String?;
      final list = (jsonDecode(raw ?? '[]') as List).cast<Map<String, dynamic>>();
      setState(() => _items = list);
    } catch (_) {}
  }

  Future<void> _pick() async {
    if (_importing) return;
    setState(() {
      _importing = true;
      _status = '选择音源文件…';
    });
    try {
      final raw = await Bridge.call('pickSource') as String?;
      final list = (jsonDecode(raw ?? '[]') as List).cast<Map<String, dynamic>>();
      setState(() {
        _items = list;
        _status = '已导入 ${list.length} 个音源';
      });
    } catch (e) {
      setState(() => _status = e.toString().contains('没有选择') ? '已取消' : Bridge.err(e));
    } finally {
      if (mounted) setState(() => _importing = false);
    }
  }

  Future<void> _paste() async {
    final text = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('粘贴音源'),
        content: TextField(
          controller: text,
          maxLines: 6,
          decoration: const InputDecoration(hintText: '脚本内容或 URL'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('取消')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('导入')),
        ],
      ),
    );
    if (ok != true) return;
    try {
      final raw = await Bridge.call('importSource', {'text': text.text}) as String?;
      final list = (jsonDecode(raw ?? '[]') as List).cast<Map<String, dynamic>>();
      setState(() {
        _items = list;
        _status = '已导入';
      });
    } catch (e) {
      setState(() => _status = Bridge.err(e));
    }
  }

  Future<void> _probe() async {
    if (_probing) return;
    setState(() {
      _probing = true;
      _status = '检测中…';
    });
    try {
      final raw = await Bridge.call('probeSources') as String?;
      final list = (jsonDecode(raw ?? '[]') as List).cast<Map<String, dynamic>>();
      final map = <String, Map<String, dynamic>>{};
      for (final e in list) {
        map[e['name'] as String? ?? ''] = e;
      }
      setState(() {
        _health = map;
        _status = '检测完成';
      });
    } catch (e) {
      setState(() => _status = Bridge.err(e));
    } finally {
      if (mounted) setState(() => _probing = false);
    }
  }

  Color _colorOf(String hex) {
    final v = int.tryParse(hex.replaceFirst('#', ''), radix: 16) ?? 0x3A7AF0;
    return Color(0xFF000000 | v);
  }

  void _setTheme(String hex) {
    final h = hex.toUpperCase();
    setState(() => _theme = h);
    widget.themeSeed.value = _colorOf(h);
    _save({'theme': h});
  }

  Future<void> _saveNet() async {
    await _save({'mirror': _mirror.text.trim(), 'proxy': _proxy.text.trim()});
  }

  Future<void> _save(Map<String, dynamic> patch) async {
    try {
      await Bridge.call('setConfig', {'json': jsonEncode(patch)});
      if (mounted) setState(() => _status = '已保存');
    } catch (e) {
      if (mounted) setState(() => _status = Bridge.err(e));
    }
  }

  Future<void> _pickDir() async {
    try {
      final uri = await Bridge.call('pickOutDir') as String?;
      if (uri != null) {
        setState(() => _outTree = uri);
        setState(() => _status = '已选择导出目录');
      }
    } catch (e) {
      setState(() => _status = e.toString().contains('没有选择') ? '已取消' : Bridge.err(e));
    }
  }

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 12),
      children: [
        _settingsCard(),
        const SizedBox(height: 8),
        Row(children: [
          Expanded(
            child: AnimatedSwitcher(
              duration: const Duration(milliseconds: 220),
              child: Text(
                _status,
                key: ValueKey(_status),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: Theme.of(context).textTheme.labelSmall,
              ),
            ),
          ),
          IconButton(tooltip: '粘贴', onPressed: _paste, icon: const Icon(Icons.content_paste, size: 18)),
          IconButton(tooltip: '测速', onPressed: _probing ? null : _probe,
              icon: _probing
                  ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(Icons.speed, size: 18)),
          IconButton(tooltip: '刷新', onPressed: _load, icon: const Icon(Icons.refresh, size: 18)),
        ]),
        Row(children: [
          FilledButton.tonalIcon(
            onPressed: _importing ? null : _pick,
            icon: _importing
                ? const SizedBox(width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2))
                : const Icon(Icons.folder_open, size: 16),
            label: const Text('导入音源'),
          ),
        ]),
        const SizedBox(height: 6),
        if (_items.isEmpty)
          const Padding(padding: EdgeInsets.all(24), child: Center(child: Text('空'))),
        for (var i = 0; i < _items.length; i++) _sourceRow(i),
      ],
    );
  }

  Widget _settingsCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(10),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            const Text('镜像', style: TextStyle(fontWeight: FontWeight.w600)),
            const SizedBox(width: 8),
            Expanded(
              child: TextField(
                controller: _mirror,
                decoration: const InputDecoration(hintText: '可选，API 反代地址'),
              ),
            ),
          ]),
          const SizedBox(height: 6),
          Row(children: [
            const Text('代理', style: TextStyle(fontWeight: FontWeight.w600)),
            const SizedBox(width: 8),
            Expanded(
              child: TextField(
                controller: _proxy,
                decoration: const InputDecoration(hintText: '可选，127.0.0.1:7890'),
              ),
            ),
            const SizedBox(width: 6),
            FilledButton.tonal(onPressed: _saveNet, child: const Text('保存')),
          ]),
          const Divider(height: 16),
          Row(children: [
            const Text('并发'),
            const SizedBox(width: 8),
            DropdownButtonHideUnderline(
              child: DropdownButton<int>(
                value: _concurrency.clamp(1, 3).toInt(),
                isDense: true,
                items: const [
                  DropdownMenuItem(value: 1, child: Text('1')),
                  DropdownMenuItem(value: 2, child: Text('2')),
                  DropdownMenuItem(value: 3, child: Text('3')),
                ],
                onChanged: (v) {
                  setState(() => _concurrency = v ?? 1);
                  _save({'concurrency': _concurrency});
                },
              ),
            ),
            const Spacer(),
            const Text('仅 Wi-Fi'),
            Switch(
              value: _wifiOnly,
              onChanged: (v) {
                setState(() => _wifiOnly = v);
                _save({'wifiOnly': v});
              },
            ),
          ]),
          Row(children: [
            const Text('歌词另存 .lrc'),
            Switch(
              value: _lrcSidecar,
              onChanged: (v) {
                setState(() => _lrcSidecar = v);
                _save({'lrcSidecar': v});
              },
            ),
            const Spacer(),
            TextButton.icon(
              onPressed: _pickDir,
              icon: const Icon(Icons.folder_outlined, size: 16),
              label: Text(_outTree.isEmpty ? '导出目录：默认' : '导出目录：已选'),
            ),
          ]),
          const SizedBox(height: 4),
          Row(children: [
            const Text('主题色'),
            const SizedBox(width: 10),
            Expanded(
              child: Wrap(
                spacing: 8,
                runSpacing: 6,
                children: [
                  for (final (name, hex) in _themePresets)
                    Tooltip(
                      message: name,
                      child: InkWell(
                        onTap: () => _setTheme(hex),
                        customBorder: const CircleBorder(),
                        child: Container(
                          width: 24,
                          height: 24,
                          decoration: BoxDecoration(
                            color: _colorOf(hex),
                            shape: BoxShape.circle,
                            border: _theme == hex.toUpperCase()
                                ? Border.all(
                                    color: Theme.of(context).colorScheme.onSurface,
                                    width: 2,
                                  )
                                : null,
                          ),
                        ),
                      ),
                    ),
                ],
              ),
            ),
          ]),
        ]),
      ),
    );
  }

  Widget _sourceRow(int i) {
    final s = _items[i];
    final name = s['name'] as String? ?? '';
    final ver = s['version'] as String? ?? '';
    final author = s['author'] as String? ?? '';
    final health = _health[name];
    final base = [if (ver.isNotEmpty) 'v$ver', if (author.isNotEmpty) author].join(' · ');
    final hint = health == null
        ? base
        : (health['ok'] == true
            ? '✓ ${((health['ms'] as num? ?? 0) / 1000).toStringAsFixed(1)}s · ${(health['platforms'] as List? ?? []).join('/')}'
            : '✗ ${health['error'] ?? '不可用'}');
    return TweenAnimationBuilder<double>(
      key: ValueKey('$name-$i'),
      tween: Tween(begin: 0.0, end: 1.0),
      duration: const Duration(milliseconds: 200),
      builder: (_, value, child) => Opacity(
        opacity: value,
        child: Transform.translate(offset: Offset(0, (1 - value) * 8), child: child),
      ),
      child: Container(
        margin: const EdgeInsets.only(bottom: 8),
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.surfaceContainerLow.withValues(alpha: 0.85),
          borderRadius: BorderRadius.circular(18),
          border: Border.all(
            color: Theme.of(context).colorScheme.outlineVariant.withValues(alpha: 0.35),
          ),
        ),
        child: ListTile(
          dense: true,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
          leading: Text(
            '${i + 1}.',
            style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant),
          ),
          title: Text(name, maxLines: 1, overflow: TextOverflow.ellipsis),
          subtitle: hint.isEmpty ? null : Text(hint, maxLines: 1, overflow: TextOverflow.ellipsis),
          trailing: Row(mainAxisSize: MainAxisSize.min, children: [
            IconButton(
              visualDensity: VisualDensity.compact,
              onPressed: i > 0 ? () => _move(name, -1) : null,
              icon: const Icon(Icons.arrow_upward, size: 18),
            ),
            IconButton(
              visualDensity: VisualDensity.compact,
              onPressed: i < _items.length - 1 ? () => _move(name, 1) : null,
              icon: const Icon(Icons.arrow_downward, size: 18),
            ),
            IconButton(
              visualDensity: VisualDensity.compact,
              onPressed: () => _delete(name),
              icon: const Icon(Icons.delete_outline, size: 18),
            ),
          ]),
        ),
      ),
    );
  }
}
