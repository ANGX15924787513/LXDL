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
    if (_filter == 1) return s == 'downloading';
    if (_filter == 2) return s == 'done' || s == 'deleted';
    return true;
  }

  String _bytes(num n) {
    if (n >= 1 << 30) return '${(n / (1 << 30)).toStringAsFixed(1)} GB';
    if (n >= 1 << 20) return '${(n / (1 << 20)).toStringAsFixed(1)} MB';
    if (n >= 1 << 10) return '${(n / (1 << 10)).toStringAsFixed(1)} KB';
    return '$n B';
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
      Expanded(
        child: shown.isEmpty
            ? const Center(child: Text('空'))
            : ListView.builder(
                padding: const EdgeInsets.symmetric(horizontal: 12),
                itemCount: shown.length,
                itemBuilder: (_, i) => _card(shown[i]),
              ),
      ),
    ]);
  }

  Widget _card(Map<String, dynamic> it) {
    final status = it['status'] as String? ?? '';
    final downloading = status == 'downloading';
    final failed = status == 'failed';
    final deleted = status == 'deleted';
    final done = status == 'done';
    final color = downloading
        ? const Color(0xFFDCE9FB)
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

    final statusText = downloading
        ? (totalBytes > 0 ? '${_bytes(doneBytes)} / ${_bytes(totalBytes)}' : _bytes(doneBytes))
        : done
            ? '已完成'
            : deleted
                ? '已删除'
                : (error.isEmpty ? '失败' : error);

    return AnimatedContainer(
      duration: const Duration(milliseconds: 260),
      curve: Curves.easeOut,
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(color: color, borderRadius: BorderRadius.circular(10)),
      child: downloading
          ? Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(title, maxLines: 1, overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontWeight: FontWeight.w600)),
              const SizedBox(height: 2),
              Text(artist, maxLines: 1, overflow: TextOverflow.ellipsis,
                  style: Theme.of(context).textTheme.bodySmall),
              const SizedBox(height: 6),
              TweenAnimationBuilder<double>(
                tween: Tween(begin: 0, end: progress.clamp(0.0, 1.0)),
                duration: const Duration(milliseconds: 300),
                builder: (_, value, __) =>
                    LinearProgressIndicator(value: value <= 0 ? null : value, minHeight: 5),
              ),
              const SizedBox(height: 4),
              Text(statusText, style: Theme.of(context).textTheme.labelSmall),
            ])
          : Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [
                Expanded(
                  child: Text('$title - $artist', maxLines: 1, overflow: TextOverflow.ellipsis),
                ),
                const SizedBox(width: 8),
                Text(statusText,
                    style: TextStyle(fontSize: 11, color: failed || deleted ? Colors.red : Colors.green[800])),
              ]),
              if (path.isNotEmpty)
                Text(path, maxLines: 1, overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.labelSmall),
            ]),
    );
  }
}
