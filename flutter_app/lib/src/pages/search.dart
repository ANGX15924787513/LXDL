import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../bridge.dart';

const _platforms = ['wy', 'tx', 'kg', 'kw', 'mg'];
const _platformLabels = ['网易云', 'QQ音乐', '酷狗', '酷我', '咪咕'];
const _qualities = ['320k', '128k', 'flac', 'flac24bit'];
const _naming = [
  ('歌名 - 歌手', '{title} - {artist}'),
  ('歌手 - 歌名', '{artist} - {title}'),
  ('仅歌名', '{title}'),
];

int? _detectPlatform(String text) {
  final t = text.toLowerCase();
  if (t.contains('163.com') || t.contains('163cn.tv') || t.contains('music.163')) return 0;
  if (t.contains('y.qq.com') || t.contains('qq.com')) return 1;
  if (t.contains('kugou')) return 2;
  if (t.contains('kuwo')) return 3;
  if (t.contains('migu')) return 4;
  return null;
}

class SearchPage extends StatefulWidget {
  final ValueNotifier<int> tick;
  final ValueNotifier<String> shareIn;
  final VoidCallback onStarted;
  const SearchPage({
    super.key,
    required this.tick,
    required this.shareIn,
    required this.onStarted,
  });

  @override
  State<SearchPage> createState() => _SearchPageState();
}

class _SearchPageState extends State<SearchPage> {
  int _platform = 0;
  int _mode = 0;
  int _kind = 0;
  bool _busy = false;
  bool _playing = false;
  String? _error;
  List<Map<String, dynamic>> _results = [];
  int _selected = -1;
  Map<String, dynamic>? _preview;
  int _quality = 0;
  int _namingIndex = 0;
  String _customTemplate = '{title} - {artist}';
  bool _lyrics = true;
  bool _hasMirror = false;
  List<String> _history = [];
  final _keyword = TextEditingController();
  final _share = TextEditingController();
  final _dir = TextEditingController(text: 'lxdl');

  @override
  void initState() {
    super.initState();
    _loadConfig();
    widget.shareIn.addListener(_onShare);
  }

  @override
  void dispose() {
    widget.shareIn.removeListener(_onShare);
    super.dispose();
  }

  void _onShare() {
    final text = widget.shareIn.value.trim();
    if (text.isEmpty) return;
    final idx = _detectPlatform(text);
    setState(() {
      if (idx != null) _platform = idx;
      _mode = 1;
      _share.text = text;
      _error = null;
    });
    _parse();
  }

  Future<void> _loadConfig() async {
    try {
      final raw = await Bridge.call('config') as String?;
      final cfg = jsonDecode(raw ?? '{}') as Map<String, dynamic>;
      if (!mounted) return;
      setState(() {
        _dir.text = cfg['outDir'] as String? ?? 'lxdl';
        _lyrics = cfg['lyrics'] as bool? ?? true;
        _history = ((cfg['history'] as List?) ?? []).cast<String>();
        _hasMirror = ((cfg['mirror'] as String?) ?? '').trim().isNotEmpty ||
            ((cfg['proxy'] as String?) ?? '').trim().isNotEmpty;
        final q = cfg['quality'] as String? ?? '320k';
        _quality = _qualities.indexOf(q).clamp(0, 3).toInt();
        final n = cfg['naming'] as String? ?? _naming[0].$2;
        final idx = _naming.indexWhere((e) => e.$2 == n);
        if (idx >= 0) {
          _namingIndex = idx;
        } else {
          _namingIndex = 3;
          _customTemplate = n;
        }
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
        _history = ((cfg['history'] as List?) ?? []).cast<String>();
      });
    } catch (_) {}
  }

  String get _platformKey => _platforms[_platform];
  String get _template => _namingIndex == 3 ? _customTemplate : _naming[_namingIndex].$2;

  Future<void> _pasteAndParse() async {
    final data = await Clipboard.getData(Clipboard.kTextPlain);
    final text = (data?.text ?? '').trim();
    if (text.isEmpty) {
      setState(() => _error = '剪贴板为空');
      return;
    }
    final idx = _detectPlatform(text);
    setState(() {
      if (idx != null) _platform = idx;
      _mode = 1;
      _share.text = text;
    });
    await _parse();
  }

  Future<void> _search() async {
    if (_keyword.text.trim().isEmpty) return;
    setState(() {
      _busy = true;
      _error = null;
      _results = [];
      _selected = -1;
      _preview = null;
      _playing = false;
    });
    await _stopPreview();
    try {
      final raw = await Bridge.call('search', {
        'platform': _platformKey,
        'keyword': _keyword.text.trim(),
        'playlist': _kind == 1,
      }) as String;
      final list = (jsonDecode(raw) as List).cast<Map<String, dynamic>>();
      setState(() => _results = list);
      if (list.isEmpty) setState(() => _error = '没有结果');
      if (list.isNotEmpty) await _select(0);
      await _refreshMirror();
    } catch (e) {
      setState(() => _error = Bridge.err(e));
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
      _playing = false;
    });
    await _stopPreview();
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
      await _refreshMirror();
    } catch (e) {
      setState(() => _error = Bridge.err(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _select(int i) async {
    if (i < 0 || i >= _results.length) return;
    await _stopPreview();
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

  Future<void> _stopPreview() async {
    if (!_playing) return;
    _playing = false;
    try {
      await Bridge.call('stopPreview');
    } catch (_) {}
  }

  Future<void> _togglePreview() async {
    if (_playing) {
      setState(() => _playing = false);
      await _stopPreview();
      return;
    }
    final t = _preview;
    if (t == null) return;
    setState(() => _busy = true);
    try {
      final url = await Bridge.call('resolveUrl', {
        'track': jsonEncode(t),
        'quality': _qualities[_quality],
      }) as String;
      await Bridge.call('playPreview', {
        'url': url,
        'platform': t['platform'] ?? '',
      });
      if (mounted) setState(() => _playing = true);
    } catch (e) {
      if (mounted) setState(() => _error = '试听失败：${Bridge.err(e)}');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _copyLink() async {
    final t = _preview;
    if (t == null) return;
    setState(() => _busy = true);
    try {
      final url = await Bridge.call('resolveUrl', {
        'track': jsonEncode(t),
        'quality': _qualities[_quality],
      }) as String;
      await Clipboard.setData(ClipboardData(text: url));
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('直链已复制')),
      );
    } catch (e) {
      if (mounted) setState(() => _error = Bridge.err(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _editTemplate() async {
    final ctrl = TextEditingController(text: _customTemplate);
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('命名模板'),
        content: TextField(
          controller: ctrl,
          decoration: const InputDecoration(
            hintText: '{title} - {artist}',
            helperText: '{title} {artist} {album} {platform} {quality} {id}',
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('取消')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('保存')),
        ],
      ),
    );
    if (ok == true) {
      setState(() {
        _customTemplate = ctrl.text.trim().isEmpty ? '{title} - {artist}' : ctrl.text.trim();
        _namingIndex = 3;
      });
    }
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
    if (t['playlist'] == true) {
      await _pickPlaylist(t);
      return;
    }
    setState(() => _busy = true);
    try {
      await Bridge.call('startDownload', {
        'track': jsonEncode(t),
        'quality': _qualities[_quality],
        'naming': _template,
        'outDir': _dir.text.trim().isEmpty ? 'lxdl' : _dir.text.trim(),
        'lyrics': _lyrics,
      });
      widget.onStarted();
    } catch (e) {
      setState(() => _error = Bridge.err(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _pickPlaylist(Map<String, dynamic> t) async {
    setState(() => _busy = true);
    List<Map<String, dynamic>> tracks = [];
    try {
      final raw = await Bridge.call('playlistTracks', {
        'platform': t['platform'],
        'id': t['id'],
      }) as String;
      tracks = (jsonDecode(raw) as List).cast<Map<String, dynamic>>();
    } catch (e) {
      setState(() {
        _busy = false;
        _error = '展开歌单失败：${Bridge.err(e)}';
      });
      return;
    }
    if (!mounted) return;
    setState(() => _busy = false);
    final selected = await showModalBottomSheet<List<Map<String, dynamic>>>(
      context: context,
      isScrollControlled: true,
      builder: (ctx) {
        final checked = List<bool>.filled(tracks.length, true);
        return StatefulBuilder(
          builder: (ctx, setSheet) => SizedBox(
            height: MediaQuery.of(ctx).size.height * 0.75,
            child: Column(children: [
              ListTile(
                title: Text('歌单 · ${tracks.length} 首', style: const TextStyle(fontWeight: FontWeight.w600)),
                trailing: TextButton(
                  onPressed: () {
                    final all = checked.every((e) => e);
                    setSheet(() {
                      for (var i = 0; i < checked.length; i++) checked[i] = !all;
                    });
                  },
                  child: Text(checked.every((e) => e) ? '全不选' : '全选'),
                ),
              ),
              const Divider(height: 1),
              Expanded(
                child: ListView.builder(
                  itemCount: tracks.length,
                  itemBuilder: (_, i) => CheckboxListTile(
                    dense: true,
                    value: checked[i],
                    onChanged: (v) => setSheet(() => checked[i] = v ?? false),
                    title: Text(tracks[i]['title'] as String? ?? '', maxLines: 1, overflow: TextOverflow.ellipsis),
                    subtitle: Text(tracks[i]['artist'] as String? ?? '', maxLines: 1, overflow: TextOverflow.ellipsis),
                  ),
                ),
              ),
              SafeArea(
                child: Padding(
                  padding: const EdgeInsets.all(8),
                  child: FilledButton(
                    onPressed: () {
                      final list = <Map<String, dynamic>>[];
                      for (var i = 0; i < tracks.length; i++) {
                        if (checked[i]) list.add(tracks[i]);
                      }
                      Navigator.pop(ctx, list);
                    },
                    child: Text('下载 ${checked.where((e) => e).length} 首'),
                  ),
                ),
              ),
            ]),
          ),
        );
      },
    );
    if (selected == null || selected.isEmpty) return;
    try {
      await Bridge.call('startTracks', {
        'tracks': jsonEncode(selected),
        'quality': _qualities[_quality],
        'naming': _template,
        'outDir': _dir.text.trim().isEmpty ? 'lxdl' : _dir.text.trim(),
        'lyrics': _lyrics,
      });
      widget.onStarted();
    } catch (e) {
      setState(() => _error = Bridge.err(e));
    }
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
          Expanded(
            child: DropdownButtonHideUnderline(
              child: DropdownButton<int>(
                value: _platform,
                isDense: true,
                isExpanded: true,
                items: [
                  for (var i = 0; i < _platforms.length; i++)
                    DropdownMenuItem(value: i, child: Text(_platformLabels[i])),
                ],
                onChanged: (v) => setState(() => _platform = v ?? 0),
              ),
            ),
          ),
          if (_mode == 0) ...[
            const SizedBox(width: 8),
            SizedBox(
              width: 92,
              child: DropdownButtonHideUnderline(
                child: DropdownButton<int>(
                  value: _kind,
                  isDense: true,
                  items: const [
                    DropdownMenuItem(value: 0, child: Text('歌曲')),
                    DropdownMenuItem(value: 1, child: Text('歌单')),
                  ],
                  onChanged: (v) => setState(() => _kind = v ?? 0),
                ),
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
            IconButton(
              tooltip: '粘贴链接',
              onPressed: _busy ? null : _pasteAndParse,
              icon: const Icon(Icons.content_paste),
            ),
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
            IconButton(
              tooltip: '粘贴',
              onPressed: _busy ? null : _pasteAndParse,
              icon: const Icon(Icons.content_paste),
            ),
            IconButton.filled(
              onPressed: _busy ? null : _parse,
              icon: const Icon(Icons.link),
            ),
          ]),
        if (_mode == 0 && _results.isEmpty && _history.isNotEmpty && !_busy)
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: Wrap(
              spacing: 6,
              runSpacing: -6,
              children: [
                for (final h in _history.take(8))
                  ActionChip(
                    label: Text(h, maxLines: 1, overflow: TextOverflow.ellipsis),
                    onPressed: () {
                      setState(() => _keyword.text = h);
                      _search();
                    },
                  ),
              ],
            ),
          ),
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
                    : Image.network(
                        cover,
                        fit: BoxFit.cover,
                        errorBuilder: (_, __, ___) =>
                            Container(color: Colors.black12, child: const Icon(Icons.music_note)),
                      ),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(
                  '${t['title'] ?? ''}',
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
                const SizedBox(height: 2),
                Text('${t['artist'] ?? ''}', maxLines: 1, overflow: TextOverflow.ellipsis),
                Text('${t['meta'] ?? ''}', style: Theme.of(context).textTheme.bodySmall),
              ]),
            ),
            IconButton(
              tooltip: _playing ? '停止试听' : '试听',
              onPressed: _busy ? null : _togglePreview,
              icon: Icon(_playing ? Icons.stop_circle_outlined : Icons.play_circle_outline),
            ),
          ]),
          const SizedBox(height: 8),
          Row(children: [
            Expanded(
              child: DropdownButtonHideUnderline(
                child: DropdownButton<int>(
                  value: _quality,
                  isDense: true,
                  isExpanded: true,
                  items: const [
                    DropdownMenuItem(value: 0, child: Text('320k')),
                    DropdownMenuItem(value: 1, child: Text('128k')),
                    DropdownMenuItem(value: 2, child: Text('FLAC')),
                    DropdownMenuItem(value: 3, child: Text('FLAC 24bit')),
                  ],
                  onChanged: (v) => setState(() => _quality = v ?? 0),
                ),
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: DropdownButtonHideUnderline(
                child: DropdownButton<int>(
                  value: _namingIndex,
                  isDense: true,
                  isExpanded: true,
                  items: [
                    for (var i = 0; i < _naming.length; i++)
                      DropdownMenuItem(value: i, child: Text(_naming[i].$1, overflow: TextOverflow.ellipsis)),
                    const DropdownMenuItem(value: 3, child: Text('自定义…')),
                  ],
                  onChanged: (v) {
                    if (v == 3) {
                      _editTemplate();
                    } else {
                      setState(() => _namingIndex = v ?? 0);
                    }
                  },
                ),
              ),
            ),
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
            IconButton(
              tooltip: '复制直链',
              onPressed: _busy ? null : _copyLink,
              icon: const Icon(Icons.link),
            ),
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
