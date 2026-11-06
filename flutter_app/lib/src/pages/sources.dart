import 'dart:convert';
import 'package:flutter/material.dart';
import '../bridge.dart';

class SourcesPage extends StatefulWidget {
  const SourcesPage({super.key});

  @override
  State<SourcesPage> createState() => _SourcesPageState();
}

class _SourcesPageState extends State<SourcesPage> {
  List<Map<String, dynamic>> _items = [];
  final _mirror = TextEditingController();
  final _proxy = TextEditingController();
  String _status = '';

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
      if (mounted) setState(() => _status = '$e');
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

  Future<void> _import() async {
    final text = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('导入音源'),
        content: TextField(
          controller: text,
          maxLines: 6,
          decoration: const InputDecoration(hintText: '粘贴脚本或 URL'),
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
      setState(() => _status = '$e');
    }
  }

  Future<void> _saveNet() async {
    try {
      await Bridge.call('setConfig', {
        'json': jsonEncode({'mirror': _mirror.text.trim(), 'proxy': _proxy.text.trim()}),
      });
      setState(() => _status = '已保存');
    } catch (e) {
      setState(() => _status = '$e');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Column(children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(12, 8, 12, 0),
        child: Column(children: [
          Row(children: [
            const Text('镜像'),
            const SizedBox(width: 8),
            Expanded(
              child: TextField(
                controller: _mirror,
                decoration: const InputDecoration(
                  isDense: true,
                  hintText: '可选，如 https://api.example.com',
                  border: OutlineInputBorder(),
                ),
              ),
            ),
          ]),
          const SizedBox(height: 6),
          Row(children: [
            const Text('代理'),
            const SizedBox(width: 8),
            Expanded(
              child: TextField(
                controller: _proxy,
                decoration: const InputDecoration(
                  isDense: true,
                  hintText: '可选，如 127.0.0.1:7890',
                  border: OutlineInputBorder(),
                ),
              ),
            ),
            const SizedBox(width: 6),
            FilledButton.tonal(onPressed: _saveNet, child: const Text('保存')),
          ]),
        ]),
      ),
      Padding(
        padding: const EdgeInsets.fromLTRB(12, 6, 12, 0),
        child: Row(children: [
          Expanded(child: Text(_status, style: Theme.of(context).textTheme.labelSmall)),
          TextButton(onPressed: _load, child: const Text('刷新')),
          FilledButton.tonalIcon(
            onPressed: _import,
            icon: const Icon(Icons.add, size: 16),
            label: const Text('导入'),
          ),
        ]),
      ),
      Expanded(
        child: _items.isEmpty
            ? const Center(child: Text('空'))
            : ListView.builder(
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                itemCount: _items.length,
                itemBuilder: (_, i) {
                  final s = _items[i];
                  final name = s['name'] as String? ?? '';
                  final ver = s['version'] as String? ?? '';
                  final author = s['author'] as String? ?? '';
                  final detail = [if (ver.isNotEmpty) 'v$ver', if (author.isNotEmpty) author].join(' · ');
                  return Container(
                    margin: const EdgeInsets.only(bottom: 6),
                    decoration: BoxDecoration(
                      color: Colors.white,
                      borderRadius: BorderRadius.circular(10),
                    ),
                    child: ListTile(
                      dense: true,
                      leading: Text('${i + 1}.', style: const TextStyle(color: Colors.black45)),
                      title: Text(name, maxLines: 1, overflow: TextOverflow.ellipsis),
                      subtitle: detail.isEmpty ? null : Text(detail, maxLines: 1, overflow: TextOverflow.ellipsis),
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
                  );
                },
              ),
      ),
    ]);
  }
}
