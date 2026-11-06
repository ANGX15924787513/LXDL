import 'dart:convert';
import 'package:flutter/material.dart';
import '../bridge.dart';

const _platforms = ['wy', 'tx', 'kg', 'kw', 'mg'];
const _platformLabels = ['网易云', 'QQ音乐', '酷狗', '酷我', '咪咕'];
const _naming = [
  ('歌名 - 歌手', '{title} - {artist}'),
  ('歌手 - 歌名', '{artist} - {title}'),
  ('仅歌名', '{title}'),
];

class SearchPage extends StatefulWidget {
  final ValueNotifier<int> tick;
  final VoidCallback onStarted;
  const SearchPage({super.key, required this.tick, required this.onStarted});

  @override
  State<SearchPage> createState() => _SearchPageState();
}

class _SearchPageState extends State<SearchPage> {
  int _platform = 0;
  int _mode = 0;
  int _kind = 0;
  bool _busy = false;
  String? _error;
  List<Map<String, dynamic>> _results = [];
  int _selected = -1;
  Map<String, dynamic>? _preview;
  int _quality = 0;
  int _namingIndex = 0;
  bool _lyrics = true;
  final _keyword = TextEditingController();
  final _share = TextEditingController();
  final _dir = TextEditingController(text: 'lxdl');
  bool _hasMirror = false;

  @override
  void initState() {
    super.initState();
    _loadConfig();
  }

  Future<void> _loadConfig() async {
    try {
      final raw = await Bridge.call('config') as String?;
      final cfg = jsonDecode(raw ?? '{}') as Map<String, dynamic>;
      if (!mounted) return;
      setState(() {
        _dir.text = cfg['outDir'] as String? ?? 'lxdl';
        _lyrics = cfg['lyrics'] as bool? ?? true;
        _hasMirror = ((cfg['mirror'] as String?) ?? '').trim().isNotEmpty ||
            ((cfg['proxy'] as String?) ?? '').trim().isNotEmpty;
        final q = cfg['quality'] as String? ?? '320k';
        _quality = ['320k', '128k', 'flac', 'flac24bit'].indexOf(q).clamp(0, 3);
        final n = cfg['naming'] as String? ?? _naming[0].$2;
        final idx = _naming.indexWhere((e) => e.$2 == n);
        if (idx >= 0) _namingIndex = idx;
      });
    } catch (_) {}
  }

  Future<void> _refreshMirror() async {
    try {
      final raw = await Bridge.call('config') as String?;
      final cfg = jsonDecode(raw ?? '{}') as Map<String, dynamic>;
      if (!mounted) return;
      setState(() {
        _hasMirror = ((cfg['mirror'] as String?) ?? '').trim().isNotEmpty ||
            ((cfg['proxy'] as String?) ?? '').trim().isNotEmpty;
      });
    } catch (_) {}
  }

  String get _platformKey => _platforms[_platform];

  Future<void> _search() async {
    if (_keyword.text.trim().isEmpty) return;
    setState(() {
      _busy = true;
      _error = null;
      _results = [];
      _selected = -1;
      _preview = null;
    });
    try {
      final raw = await Bridge.call('search', {
        'platform': _platformKey,
        'keyword': _keyword.text.trim(),
        'playlist': _kind == 1,
      }) as String;
      final list = (jsonDecode(raw) as List).cast<Map<String, dynamic>>();
      setState(() => _results = list);
      if (list.isNotEmpty) await _select(0);
      if (list.isEmpty) setState(() => _error = '没有结果');
    } catch (e) {
      setState(() => _error = '$e');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _parse() async {
    if (_share.text.trim().isEmpty) return;
    setState(() {
      _busy = true;
      _error = null;
      _results = [];
      _selected = -1;
      _preview = null;
    });
    try {
      final raw = await Bridge.call('parse', {
        'platform': _platformKey,
        'text': _share.text.trim(),
      }) as String;
      final t = jsonDecode(raw) as Map<String, dynamic>;
      setState(() {
        _results = [t];
        _selected = 0;
        _preview = t;
      });
    } catch (e) {
      setState(() => _error = '无法解析');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _select(int i) async {
    if (i < 0 || i >= _results.length) return;
    setState(() {
      _selected = i;
      _preview = _results[i];
    });
    final t = _results[i];
    if (t['playlist'] == true) return;
    try {
      final raw = await Bridge.call('detail', {
        'platform': t['platform'],
        'id': t['id'],
      }) as String;
      final d = jsonDecode(raw) as Map<String, dynamic>;
      if (!mounted || _selected != i) return;
      setState(() {
        _preview = {
          ...t,
          'title': d['title'] ?? t['title'],
          'artist': d['artist'] ?? t['artist'],
          'album': d['album'] ?? t['album'],
          'cover': d['cover'] ?? t['cover'],
          'duration': d['duration'] ?? t['duration'],
        };
      });
    } catch (_) {}
  }

  Future<void> _download() async {
    final t = _preview;
    if (t == null) return;
    await _refreshMirror();
    if (!_hasMirror) {
      final go = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          content: const Text('未配置镜像/代理，下载可能很慢'),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('取消')),
            FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('继续')),
          ],
        ),
      );
      if (go != true) return;
    }
    setState(() => _busy = true);
    try {
      await Bridge.call('startDownload', {
        'track': jsonEncode(t),
        'quality': ['320k', '128k', 'flac', 'flac24bit'][_quality],
        'naming': _naming[_namingIndex].$2,
        'outDir': _dir.text.trim().isEmpty ? 'lxdl' : _dir.text.trim(),
        'lyrics': _lyrics,
      });
      widget.onStarted();
    } catch (e) {
      setState(() => _error = '$e');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  DropdownMenuItem<int> _item(int value, String text) =>
      DropdownMenuItem(value: value, child: Text(text, overflow: TextOverflow.ellipsis));

  Widget _dd({
    required int value,
    required List<DropdownMenuItem<int>> items,
    required ValueChanged<int?> onChanged,
  }) {
    return DropdownButtonHideUnderline(
      child: DropdownButton<int>(
        value: value,
        isDense: true,
        isExpanded: true,
        items: items,
        onChanged: onChanged,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 12),
      children: [
        SegmentedButton<int>(
          segments: const [
            ButtonSegment(value: 0, label: Text('搜索')),
            ButtonSegment(value: 1, label: Text('链接')),
          ],
          selected: {_mode},
          showSelectedIcon: false,
          onSelectionChanged: (v) => setState(() {
            _mode = v.first;
            _error = null;
          }),
        ),
        const SizedBox(height: 8),
        Row(children: [
          Expanded(child: _dd(
            value: _platform,
            items: [for (var i = 0; i < _platforms.length; i++) _item(i, _platformLabels[i])],
            onChanged: (v) => setState(() => _platform = v ?? 0),
          )),
          if (_mode == 0) ...[
            const SizedBox(width: 8),
            SizedBox(
              width: 92,
              child: _dd(
                value: _kind,
                items: [_item(0, '歌曲'), _item(1, '歌单')],
                onChanged: (v) => setState(() => _kind = v ?? 0),
              ),
            ),
          ],
        ]),
        const SizedBox(height: 8),
        if (_mode == 0)
          Row(children: [
            Expanded(
              child: TextField(
                controller: _keyword,
                decoration: const InputDecoration(hintText: '关键词', isDense: true, border: OutlineInputBorder()),
                onSubmitted: (_) => _search(),
              ),
            ),
            const SizedBox(width: 8),
            IconButton.filled(
              onPressed: _busy ? null : _search,
              icon: const Icon(Icons.search),
            ),
          ])
        else
          Row(children: [
            Expanded(
              child: TextField(
                controller: _share,
                decoration: const InputDecoration(hintText: '粘贴分享链接或 id', isDense: true, border: OutlineInputBorder()),
                onSubmitted: (_) => _parse(),
              ),
            ),
            const SizedBox(width: 8),
            IconButton.filled(
              onPressed: _busy ? null : _parse,
              icon: const Icon(Icons.link),
            ),
          ]),
        if (_error != null)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
          ),
        AnimatedSize(
          duration: const Duration(milliseconds: 220),
          curve: Curves.easeOut,
          child: _results.isEmpty
              ? const SizedBox.shrink()
              : SizedBox(
            height: 200,
            child: ListView.builder(
              itemCount: _results.length,
              itemBuilder: (_, i) {
                final t = _results[i];
                return ListTile(
                  dense: true,
                  selected: i == _selected,
                  title: Text('${t['title'] ?? ''}', maxLines: 1, overflow: TextOverflow.ellipsis),
                  subtitle: Text(
                    '${t['artist'] ?? ''}   ${t['meta'] ?? ''}',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  onTap: () => _select(i),
                );
              },
            ),
          ),
        ),
        AnimatedSwitcher(
          duration: const Duration(milliseconds: 260),
          switchInCurve: Curves.easeOut,
          switchOutCurve: Curves.easeIn,
          transitionBuilder: (child, anim) => FadeTransition(
            opacity: anim,
            child: ScaleTransition(
              scale: Tween<double>(begin: 0.97, end: 1).animate(anim),
              child: child,
            ),
          ),
          child: _preview == null
              ? const SizedBox.shrink(key: ValueKey('empty'))
              : KeyedSubtree(
                  key: ValueKey('${_preview!['id']}-${_preview!['title']}'),
                  child: _previewCard(_preview!),
                ),
        ),
      ],
    );
  }

  Widget _previewCard(Map<String, dynamic> t) {
    final cover = t['cover'] as String?;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(10),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            ClipRRect(
              borderRadius: BorderRadius.circular(8),
              child: SizedBox(
                width: 84,
                height: 84,
                child: cover == null || cover.isEmpty
                    ? Container(color: Colors.black12, child: const Icon(Icons.music_note))
                    : Image.network(cover, fit: BoxFit.cover,
                        errorBuilder: (_, __, ___) =>
                            Container(color: Colors.black12, child: const Icon(Icons.music_note))),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text('${t['title'] ?? ''}', maxLines: 2, overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontWeight: FontWeight.w600)),
                const SizedBox(height: 2),
                Text('${t['artist'] ?? ''}', maxLines: 1, overflow: TextOverflow.ellipsis),
                Text('${t['meta'] ?? ''}', style: Theme.of(context).textTheme.bodySmall),
              ]),
            ),
          ]),
          const SizedBox(height: 8),
          Row(children: [
            Expanded(child: _dd(
              value: _quality,
              items: [_item(0, '320k'), _item(1, '128k'), _item(2, 'FLAC'), _item(3, 'FLAC 24bit')],
              onChanged: (v) => setState(() => _quality = v ?? 0),
            )),
            const SizedBox(width: 8),
            Expanded(child: _dd(
              value: _namingIndex,
              items: [for (var i = 0; i < _naming.length; i++) _item(i, _naming[i].$1)],
              onChanged: (v) => setState(() => _namingIndex = v ?? 0),
            )),
          ]),
          const SizedBox(height: 8),
          TextField(
            controller: _dir,
            decoration: const InputDecoration(
              isDense: true,
              labelText: '目录（Download 下）',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 4),
          Row(children: [
            Checkbox(
              value: _lyrics,
              onChanged: (v) => setState(() => _lyrics = v ?? true),
            ),
            const Text('歌词'),
            const Spacer(),
            FilledButton.icon(
              onPressed: _busy ? null : _download,
              icon: const Icon(Icons.download, size: 18),
              label: const Text('下载'),
            ),
          ]),
        ]),
      ),
    );
  }
}
