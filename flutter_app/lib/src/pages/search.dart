import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../bridge.dart';
import '../cover.dart';
import '../ui.dart';

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
  // 深链接：netease://song/xxx、tencent://song/xxx ...
  if (t.contains('netease://') || t.contains('163music://') || t.contains('cloudmusic://')) return 0;
  if (t.contains('tencent://') || t.contains('qqmusic://')) return 1;
  if (t.contains('kugou://') || t.contains('kgmusic://')) return 2;
  if (t.contains('kuwo://')) return 3;
  if (t.contains('migu://')) return 4;
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
  final ValueNotifier<int> audioStop;
  /// 当前选中歌曲的封面，交给 App 当模糊背景
  final ValueNotifier<String?> coverIn;
  final VoidCallback onStarted;
  const SearchPage({
    super.key,
    required this.tick,
    required this.shareIn,
    required this.audioStop,
    required this.coverIn,
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
  static bool _warmed = false;
  int _quality = 0;
  int _namingIndex = 0;
  String _customTemplate = '{title} - {artist}';
  bool _lyrics = true;
  List<String> _history = [];
  final _keyword = TextEditingController();
  final _share = TextEditingController();
  final _dir = TextEditingController(text: 'lxdl');

  @override
  void initState() {
    super.initState();
    _loadConfig();
    widget.shareIn.addListener(_onShare);
    widget.audioStop.addListener(_onAudioStop);
  }

  @override
  void dispose() {
    widget.shareIn.removeListener(_onShare);
    widget.audioStop.removeListener(_onAudioStop);
    // 页面被销毁时别让声音继续放
    Bridge.call('stopPreview').then((_) {}, onError: (_) {});
    widget.coverIn.value = null;
    super.dispose();
  }

  /// 外部（切 tab / 切后台 / 开始下载）停了音频，这里只复位按钮状态
  void _onAudioStop() {
    if (!_playing) return;
    setState(() => _playing = false);
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
    widget.coverIn.value = null;
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
      if (list.isNotEmpty) {
        _warmUp();
        await _select(0);
      }
      await _refreshMirror();
    } catch (e) {
      setState(() => _error = Bridge.err(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _parse() async {
    final text = _share.text.trim();
    if (text.isEmpty) return;
    // 手输/粘贴深链接时把平台下拉框也对齐
    final idx = _detectPlatform(text);
    if (idx != null && idx != _platform) setState(() => _platform = idx);
    setState(() {
      _busy = true;
      _error = null;
      _results = [];
      _selected = -1;
      _preview = null;
      _playing = false;
    });
    widget.coverIn.value = null;
    await _stopPreview();
    try {
      final raw = await Bridge.call('parse', {
        'platform': _platformKey,
        'text': text,
      }) as String;
      final t = jsonDecode(raw) as Map<String, dynamic>;
      setState(() {
        _results = [t];
        _selected = 0;
        _preview = t;
      });
      widget.coverIn.value = t['cover'] as String?;
      _warmUp();
      await _refreshMirror();
    } catch (e) {
      setState(() => _error = Bridge.err(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _clearSelection() async {
    await _stopPreview();
    if (!mounted) return;
    setState(() {
      _selected = -1;
      _preview = null;
    });
    widget.coverIn.value = null;
  }

  Future<void> _select(int i) async {
    if (i < 0 || i >= _results.length) return;
    await _stopPreview();
    setState(() {
      _selected = i;
      _preview = _results[i];
    });
    widget.coverIn.value = _results[i]['cover'] as String?;
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
      widget.coverIn.value = _preview?['cover'] as String?;
    } catch (_) {}
  }

  Future<void> _stopPreview() async {
    // 注意：不能用 _playing 当守卫，否则「点停止」时已经先置 false 就停不掉了
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
            child: _fieldBox(
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
          ),
          if (_mode == 0) ...[
            const SizedBox(width: 8),
            SizedBox(
              width: 96,
              child: _fieldBox(
                child: DropdownButtonHideUnderline(
                  child: DropdownButton<int>(
                    value: _kind,
                    isDense: true,
                    isExpanded: true,
                    items: const [
                      DropdownMenuItem(value: 0, child: Text('歌曲')),
                      DropdownMenuItem(value: 1, child: Text('歌单')),
                    ],
                    onChanged: (v) => setState(() => _kind = v ?? 0),
                  ),
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
                decoration: const InputDecoration(hintText: '关键词'),
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
                decoration: const InputDecoration(hintText: '粘贴分享链接或 id'),
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
        AnimatedSize(
          duration: const Duration(milliseconds: 180),
          curve: Curves.easeOut,
          alignment: Alignment.topLeft,
          child: _error == null
              ? const SizedBox(width: double.infinity)
              : Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(
                    _error!,
                    style: TextStyle(color: Theme.of(context).colorScheme.error),
                  ),
                ),
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
                      final scheme = Theme.of(context).colorScheme;
                      return StaggeredIn(
                        index: i,
                        child: Padding(
                          padding: const EdgeInsets.symmetric(horizontal: 2, vertical: 1),
                          child: ListTile(
                            dense: true,
                            selected: i == _selected,
                            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                            selectedTileColor: scheme.secondaryContainer.withValues(alpha: 0.75),
                            contentPadding: const EdgeInsets.symmetric(horizontal: 12),
                            title: Text('${t['title'] ?? ''}', maxLines: 1, overflow: TextOverflow.ellipsis),
                            subtitle: Text(
                              '${t['artist'] ?? ''}   ${t['meta'] ?? ''}',
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                            onTap: () => i == _selected ? _clearSelection() : _select(i),
                          ),
                        ),
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

  /// 有过一次结果就预热引擎：WebView + 音源脚本提前跑好，点下载不用等冷启动
  void _warmUp() {
    if (_warmed) return;
    _warmed = true;
    Bridge.call('warmUp').then((_) {}, onError: (_) {});
  }

  /// 下拉框也包成一个圆角“输入框”，和 TextField 观感统一
  Widget _fieldBox({required Widget child}) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 2),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerHighest.withValues(alpha: 0.55),
        borderRadius: BorderRadius.circular(14),
      ),
      child: child,
    );
  }

  Widget _previewCard(Map<String, dynamic> t) {
    final cover = t['cover'] as String?;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(10),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            _CoverArt(url: cover, size: 84, gap: 10),
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
            IconButton.filledTonal(
              tooltip: _playing ? '停止试听' : '试听',
              onPressed: _busy ? null : _togglePreview,
              icon: Icon(_playing ? Icons.stop_circle_outlined : Icons.play_arrow_rounded),
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
            decoration: const InputDecoration(labelText: '目录（Download 下）'),
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

/// 封面：走原生磁盘缓存（CoverCache），没加载好之前不占位置、不留灰块。
class _CoverArt extends StatefulWidget {
  const _CoverArt({this.url, this.size = 84, this.gap = 10});

  final String? url;
  final double size;
  final double gap;

  @override
  State<_CoverArt> createState() => _CoverArtState();
}

class _CoverArtState extends State<_CoverArt> {
  String? _path;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void didUpdateWidget(covariant _CoverArt old) {
    super.didUpdateWidget(old);
    final oldUrl = (old.url ?? '').trim();
    final newUrl = (widget.url ?? '').trim();
    if (oldUrl != newUrl) {
      _path = null;
      _load();
    }
  }

  Future<void> _load() async {
    final u = (widget.url ?? '').trim();
    if (u.isEmpty) return;
    final p = await CoverStore.path(u);
    if (p == null || !mounted) return;
    if ((widget.url ?? '').trim() != u) return;
    setState(() => _path = p);
  }

  @override
  Widget build(BuildContext context) {
    final p = _path;
    if (p == null) return const SizedBox.shrink();
    return TweenAnimationBuilder<double>(
      tween: Tween(begin: 0.0, end: 1.0),
      duration: const Duration(milliseconds: 260),
      curve: Curves.easeOut,
      builder: (_, v, child) => Opacity(
        opacity: v,
        child: Transform.scale(scale: 0.94 + 0.06 * v, child: child),
      ),
      child: Padding(
        padding: EdgeInsets.only(right: widget.gap),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(12),
          child: Image.file(
          File(p),
          width: widget.size,
          height: widget.size,
          fit: BoxFit.cover,
          gaplessPlayback: true,
            frameBuilder: (context, child, frame, wasSynchronouslyLoaded) {
              if (frame == null && !wasSynchronouslyLoaded) return const SizedBox.shrink();
              return child;
            },
            errorBuilder: (_, __, ___) => const SizedBox.shrink(),
          ),
        ),
      ),
    );
  }
}
