import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import '../bridge.dart';

class DownloadsPage extends StatefulWidget {
  final ValueNotifier<int> tick;
  const DownloadsPage({super.key, required this.tick});

  @override
  State<DownloadsPage> createState() => _DownloadsPageState();
}

class _DownloadsPageState extends State<DownloadsPage> {
  List<Map<String, dynamic>> _items = [];
  int _filter = 0;
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    _timer = Timer.periodic(const Duration(milliseconds: 1500), (_) => _load());
    widget.tick.addListener(_load);
    _load();
  }

  @override
  void dispose() {
    _timer?.cancel();
    widget.tick.removeListener(_load);
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final raw = await Bridge.call('downloads') as String?;
      final list = (jsonDecode(raw ?? '[]') as List).cast<Map<String, dynamic>>();
      if (!mounted) return;
      setState(() => _items = list);
    } catch (_) {}
  }

  bool _match(Map<String, dynamic> it) {
    final s = it['status'] as String? ?? '';
    if (_filter == 1) return s == 'downloading' || s == 'paused';
    if (_filter == 2) return s == 'done' || s == 'deleted';
    return true;
  }

  String _bytes(num n) {
    if (n >= 1 << 30) return '${(n / (1 << 30)).toStringAsFixed(1)} GB';
    if (n >= 1 << 20) return '${(n / (1 << 20)).toStringAsFixed(1)} MB';
    if (n >= 1 << 10) return '${(n / (1 << 10)).toStringAsFixed(1)} KB';
    return '$n B';
  }

  String _summary() {
    final done = _items.where((e) => e['status'] == 'done').length;
    final running = _items.where((e) => e['status'] == 'downloading' || e['status'] == 'paused').length;
    final bytes = _items.fold<num>(0, (sum, e) => sum + ((e['done'] as num?) ?? 0));
    return '完成 $done · 下载中 $running · 共 ${_bytes(bytes)}';
  }

  Future<void> _call(String method, Map<String, dynamic> args) async {
    try {
      await Bridge.call(method, args);
      await _load();
    } catch (_) {}
  }

  Future<void> _delete(int key) async {
    await _call('deleteDownload', {'key': key});
  }

  Future<void> _retryFailed() async {
    final failed = _items.where((e) => e['status'] == 'failed').toList();
    for (final e in failed) {
      try {
        await Bridge.call('startDownload', {
          'track': jsonEncode({
            'platform': e['platform'],
            'id': e['id'],
            'title': e['title'],
            'artist': e['artist'],
            'album': e['album'] ?? '',
            'playlist': false,
          }),
        });
      } catch (_) {}
    }
    await _load();
  }

  Future<void> _clearDone() async {
    final done = _items.where((e) => e['status'] == 'done' || e['status'] == 'deleted').toList();
    for (final e in done) {
      try {
        await Bridge.call('deleteDownload', {'key': e['key']});
      } catch (_) {}
    }
    await _load();
  }

  Future<void> _retag(Map<String, dynamic> it) async {
    final t = TextEditingController(text: it['title'] as String? ?? '');
    final a = TextEditingController(text: it['artist'] as String? ?? '');
    final al = TextEditingController(text: it['album'] as String? ?? '');
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('编辑标签'),
        content: Column(mainAxisSize: MainAxisSize.min, children: [
          TextField(controller: t, decoration: const InputDecoration(labelText: '标题')),
          TextField(controller: a, decoration: const InputDecoration(labelText: '歌手')),
          TextField(controller: al, decoration: const InputDecoration(labelText: '专辑')),
        ]),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('取消')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('保存')),
        ],
      ),
    );
    if (ok != true) return;
    await _call('retag', {
      'key': it['key'],
      'title': t.text.trim(),
      'artist': a.text.trim(),
      'album': al.text.trim(),
    });
  }

  Future<void> _menu(Map<String, dynamic> it) async {
    final done = it['status'] == 'done';
    final action = await showModalBottomSheet<String>(
      context: context,
      builder: (ctx) => SafeArea(
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          ListTile(
            enabled: done,
            leading: const Icon(Icons.open_in_new),
            title: const Text('打开'),
            onTap: () => Navigator.pop(ctx, 'open'),
          ),
          ListTile(
            enabled: done,
            leading: const Icon(Icons.share),
            title: const Text('分享'),
            onTap: () => Navigator.pop(ctx, 'share'),
          ),
          ListTile(
            enabled: done,
            leading: const Icon(Icons.edit),
            title: const Text('编辑标签'),
            onTap: () => Navigator.pop(ctx, 'retag'),
          ),
          ListTile(
            leading: const Icon(Icons.delete_outline),
            title: const Text('删除记录'),
            onTap: () => Navigator.pop(ctx, 'delete'),
          ),
        ]),
      ),
    );
    if (!mounted || action == null) return;
    switch (action) {
      case 'open':
        await _call('openFile', {'uri': it['uri'] ?? '', 'path': it['path'] ?? ''});
        break;
      case 'share':
        await _call('shareFile', {'uri': it['uri'] ?? ''});
        break;
      case 'retag':
        await _retag(it);
        break;
      case 'delete':
        await _delete(it['key'] as int? ?? -1);
        break;
    }
  }

  @override
  Widget build(BuildContext context) {
    final shown = _items.where(_match).toList();
    return Column(children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
        child: SegmentedButton<int>(
          segments: const [
            ButtonSegment(value: 0, label: Text('全部')),
            ButtonSegment(value: 1, label: Text('下载中')),
            ButtonSegment(value: 2, label: Text('已完成')),
          ],
          selected: {_filter},
          showSelectedIcon: false,
          onSelectionChanged: (v) => setState(() => _filter = v.first),
        ),
      ),
      Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14),
        child: Row(children: [
          Expanded(child: Text(_summary(), style: Theme.of(context).textTheme.labelSmall)),
          if (_items.any((e) => e['status'] == 'failed'))
            TextButton.icon(
              onPressed: _retryFailed,
              icon: const Icon(Icons.refresh, size: 16),
              label: const Text('重试'),
            ),
          if (_items.any((e) => e['status'] == 'done' || e['status'] == 'deleted'))
            TextButton(onPressed: _clearDone, child: const Text('清空')),
        ]),
      ),
      Expanded(
        child: RefreshIndicator(
          onRefresh: _load,
          child: shown.isEmpty
              ? ListView(
                  physics: const AlwaysScrollableScrollPhysics(),
                  children: const [SizedBox(height: 120), Center(child: Text('空'))],
                )
              : ListView.builder(
                  physics: const AlwaysScrollableScrollPhysics(),
                  padding: const EdgeInsets.symmetric(horizontal: 12),
                  itemCount: shown.length,
                  itemBuilder: (_, i) => _card(shown[i]),
                ),
        ),
      ),
    ]);
  }

  Widget _card(Map<String, dynamic> it) {
    final status = it['status'] as String? ?? '';
    final downloading = status == 'downloading';
    final paused = status == 'paused';
    final failed = status == 'failed';
    final deleted = status == 'deleted';
    final done = status == 'done';
    final color = downloading
        ? const Color(0xFFDCE9FB)
        : paused
            ? const Color(0xFFFFF3D6)
            : done
                ? const Color(0xFFDDF3E4)
                : failed
                    ? const Color(0xFFFBDCDC)
                    : const Color(0xFFECEDEF);
    final title = it['title'] as String? ?? '';
    final artist = it['artist'] as String? ?? '';
    final path = it['path'] as String? ?? '';
    final progress = (it['progress'] as num?)?.toDouble() ?? 0;
    final doneBytes = (it['done'] as num?)?.toInt() ?? 0;
    final totalBytes = (it['total'] as num?)?.toInt() ?? 0;
    final error = it['error'] as String? ?? '';
    final key = it['key'] as int? ?? -1;

    final statusText = downloading
        ? (totalBytes > 0 ? '${_bytes(doneBytes)} / ${_bytes(totalBytes)}' : _bytes(doneBytes))
        : paused
            ? '已暂停'
            : done
                ? '已完成'
                : deleted
                    ? '已删除'
                    : (error.isEmpty ? '失败' : error);

    final active = downloading || paused;
    return GestureDetector(
      onLongPress: () => _menu(it),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 260),
        curve: Curves.easeOut,
        margin: const EdgeInsets.only(bottom: 8),
        padding: const EdgeInsets.fromLTRB(10, 8, 4, 8),
        decoration: BoxDecoration(color: color, borderRadius: BorderRadius.circular(10)),
        child: active
            ? Row(children: [
                Expanded(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(title, maxLines: 1, overflow: TextOverflow.ellipsis,
                        style: const TextStyle(fontWeight: FontWeight.w600)),
                    const SizedBox(height: 2),
                    Text(artist, maxLines: 1, overflow: TextOverflow.ellipsis,
                        style: Theme.of(context).textTheme.bodySmall),
                    const SizedBox(height: 6),
                    TweenAnimationBuilder<double>(
                      tween: Tween(begin: 0.0, end: progress.clamp(0.0, 1.0).toDouble()),
                      duration: const Duration(milliseconds: 300),
                      builder: (_, value, __) => LinearProgressIndicator(
                        value: value <= 0 ? null : value,
                        minHeight: 5,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(statusText, style: Theme.of(context).textTheme.labelSmall),
                  ]),
                ),
                IconButton(
                  tooltip: paused ? '继续' : '暂停',
                  onPressed: () => _call(paused ? 'resumeDownload' : 'pauseDownload', {'key': key}),
                  icon: Icon(paused ? Icons.play_arrow : Icons.pause, size: 20),
                ),
                IconButton(
                  tooltip: '取消',
                  onPressed: () => _call('cancelDownload', {'key': key}),
                  icon: const Icon(Icons.close, size: 20),
                ),
              ])
            : Row(children: [
                Expanded(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text('$title - $artist', maxLines: 1, overflow: TextOverflow.ellipsis),
                    if (path.isNotEmpty)
                      Text(path, maxLines: 1, overflow: TextOverflow.ellipsis,
                          style: Theme.of(context).textTheme.labelSmall),
                  ]),
                ),
                Text(
                  statusText,
                  style: TextStyle(fontSize: 11, color: failed || deleted ? Colors.red : Colors.green[800]),
                ),
                IconButton(
                  tooltip: '更多',
                  onPressed: () => _menu(it),
                  icon: const Icon(Icons.more_vert, size: 18),
                ),
              ]),
      ),
    );
  }
}
