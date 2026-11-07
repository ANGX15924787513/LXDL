import 'dart:convert';
import 'dart:io';
import 'dart:ui' show ImageFilter;

import 'package:flutter/material.dart';

import 'bridge.dart';
import 'cover.dart';
import 'pages/search.dart';
import 'pages/downloads.dart';
import 'pages/sources.dart';

const Color _defaultSeed = Color(0xFF3A7AF0);

/// 统一的主题：一切圆角化 + 半透明卡片（让模糊封面透一点出来）。
ThemeData _themeFor(Color seed, Brightness brightness) {
  final scheme = ColorScheme.fromSeed(seedColor: seed, brightness: brightness);
  final base = ThemeData(
    useMaterial3: true,
    colorScheme: scheme,
    brightness: brightness,
    visualDensity: VisualDensity.compact,
  );
  final r12 = RoundedRectangleBorder(borderRadius: BorderRadius.circular(12));
  final r14 = RoundedRectangleBorder(borderRadius: BorderRadius.circular(14));
  final r18 = RoundedRectangleBorder(borderRadius: BorderRadius.circular(18));
  return base.copyWith(
    scaffoldBackgroundColor: Colors.transparent,
    cardTheme: CardThemeData(
      elevation: 0,
      color: scheme.surfaceContainerLow.withValues(alpha: 0.88),
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
      clipBehavior: Clip.antiAlias,
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      isDense: true,
      fillColor: scheme.surfaceContainerHighest.withValues(alpha: 0.55),
      contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      border: OutlineInputBorder(borderRadius: BorderRadius.circular(14), borderSide: BorderSide.none),
      enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(14), borderSide: BorderSide.none),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: BorderSide(color: scheme.primary, width: 1.4),
      ),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        shape: r12,
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      ),
    ),
    textButtonTheme: TextButtonThemeData(style: TextButton.styleFrom(shape: r12)),
    outlinedButtonTheme: OutlinedButtonThemeData(style: OutlinedButton.styleFrom(shape: r12)),
    segmentedButtonTheme: SegmentedButtonThemeData(
      style: ButtonStyle(shape: WidgetStatePropertyAll(r12)),
    ),
    snackBarTheme: SnackBarThemeData(
      behavior: SnackBarBehavior.floating,
      shape: r14,
      insetPadding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
    ),
    dialogTheme: DialogThemeData(shape: r18, backgroundColor: scheme.surfaceContainerHigh),
    bottomSheetTheme: BottomSheetThemeData(
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      showDragHandle: true,
      backgroundColor: scheme.surfaceContainerHigh,
    ),
    listTileTheme: ListTileThemeData(shape: r14),
    dividerTheme: const DividerThemeData(space: 16, thickness: 0.6),
    navigationBarTheme: NavigationBarThemeData(
      height: 62,
      backgroundColor: scheme.surfaceContainer.withValues(alpha: 0.86),
      indicatorColor: scheme.primaryContainer.withValues(alpha: 0.9),
      labelBehavior: NavigationDestinationLabelBehavior.alwaysHide,
    ),
  );
}

class LxdlApp extends StatefulWidget {
  const LxdlApp({super.key});
  @override
  State<LxdlApp> createState() => _LxdlAppState();
}

class _LxdlAppState extends State<LxdlApp> with WidgetsBindingObserver {
  int _tab = 0;
  final ValueNotifier<int> _tick = ValueNotifier(0);
  final ValueNotifier<String> _shareIn = ValueNotifier('');
  // 广播「音频已被外部停掉」，让搜索页把试听按钮复位
  final ValueNotifier<int> _audioStop = ValueNotifier(0);
  // 当前选中歌曲的封面，用作整屏模糊背景
  final ValueNotifier<String?> _cover = ValueNotifier(null);
  // 试听播放进度（pos/dur/playing），由原生每 500ms 推一次
  final ValueNotifier<Map<String, dynamic>?> _previewState = ValueNotifier(null);
  // 主题色（可在「音源」页设置里改）
  final ValueNotifier<Color> _seed = ValueNotifier(_defaultSeed);
  final GlobalKey<ScaffoldMessengerState> _scaffold = GlobalKey();
  int _lastToast = 0;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    Bridge.listen();
    Bridge.onEvent = (type, data) {
      if (type == 'downloads' || type == 'progress') {
        _tick.value++;
      } else if (type == 'share') {
        _shareIn.value = data['text'] as String? ?? '';
        setState(() => _tab = 0);
      } else if (type == 'preview') {
        _previewState.value = data;
      } else if (type == 'log') {
        final text = data['text'] as String? ?? '';
        if (text.isEmpty) return;
        final important = text.contains('失败') || text.contains('不可用') || text.contains('完成');
        final now = DateTime.now().millisecondsSinceEpoch;
        if (important && now - _lastToast > 1500) {
          _lastToast = now;
          _scaffold.currentState?.showSnackBar(
            SnackBar(content: Text(text, maxLines: 2), duration: const Duration(seconds: 2)),
          );
        }
      }
    };
    _takeShare();
    _loadTheme();
    // 冷启动时插件可能还没拿到 intent，稍后再取一次
    Future.delayed(const Duration(milliseconds: 900), _takeShare);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _stopAudio();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // 切后台/锁屏就不要再响了
    if (state != AppLifecycleState.resumed) _stopAudio();
  }

  /// 离开搜索页、切后台、开始下载时统一停掉试听。
  void _stopAudio() {
    _audioStop.value++;
    Bridge.call('stopPreview').then((_) {}, onError: (_) {});
  }

  Future<void> _loadTheme() async {
    try {
      final raw = await Bridge.call('config') as String?;
      final cfg = jsonDecode(raw ?? '{}') as Map<String, dynamic>;
      final c = _colorOf(cfg['theme'] as String?);
      if (c != null) _seed.value = c;
    } catch (_) {}
  }

  static Color? _colorOf(String? hex) {
    if (hex == null) return null;
    final s = hex.trim().replaceFirst('#', '');
    if (s.length != 6) return null;
    final v = int.tryParse(s, radix: 16);
    return v == null ? null : Color(0xFF000000 | v);
  }

  Future<void> _takeShare() async {
    try {
      final text = await Bridge.call('takeShare') as String?;
      if (text != null && text.isNotEmpty) {
        _shareIn.value = text;
        if (mounted) setState(() => _tab = 0);
      }
    } catch (_) {}
  }

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<Color>(
      valueListenable: _seed,
      builder: (context, seed, _) => MaterialApp(
        scaffoldMessengerKey: _scaffold,
        debugShowCheckedModeBanner: false,
        title: 'lxdl',
        theme: _themeFor(seed, Brightness.light),
        darkTheme: _themeFor(seed, Brightness.dark),
        themeMode: ThemeMode.system,
        home: Builder(
          builder: (context) => Scaffold(
            backgroundColor: Colors.transparent,
            body: Stack(
              children: [
                // 底层：主题表面色兜底，上面再叠选中歌曲的模糊封面
                Positioned.fill(
                  child: ColoredBox(color: Theme.of(context).colorScheme.surface),
                ),
                Positioned.fill(child: _CoverBackdrop(cover: _cover)),
                Positioned.fill(
                  child: SafeArea(
                    child: Stack(
                      children: [
                        for (var i = 0; i < 3; i++)
                          IgnorePointer(
                            ignoring: _tab != i,
                            child: AnimatedOpacity(
                              opacity: _tab == i ? 1 : 0,
                              duration: const Duration(milliseconds: 180),
                              curve: Curves.easeOut,
                              child: i == 0
                                  ? SearchPage(
                                      tick: _tick,
                                      shareIn: _shareIn,
                                      audioStop: _audioStop,
                                      coverIn: _cover,
                                      previewState: _previewState,
                                      onStarted: () {
                                        _stopAudio();
                                        setState(() => _tab = 1);
                                      },
                                    )
                                  : i == 1
                                      ? DownloadsPage(tick: _tick)
                                      : SourcesPage(themeSeed: _seed),
                            ),
                          ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
            bottomNavigationBar: NavigationBar(
              height: 62,
              selectedIndex: _tab,
              labelBehavior: NavigationDestinationLabelBehavior.alwaysHide,
              onDestinationSelected: (i) {
                if (i != _tab) _stopAudio();
                setState(() => _tab = i);
              },
              destinations: const [
                NavigationDestination(icon: Icon(Icons.search), label: '搜索'),
                NavigationDestination(icon: Icon(Icons.download), label: '下载'),
                NavigationDestination(icon: Icon(Icons.cloud), label: '音源'),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// 背景层：封面解码成小图 -> 高斯模糊 -> 叠一层遮罩，选中/取消选中淡入淡出。
class _CoverBackdrop extends StatefulWidget {
  const _CoverBackdrop({required this.cover});

  final ValueNotifier<String?> cover;

  @override
  State<_CoverBackdrop> createState() => _CoverBackdropState();
}

class _CoverBackdropState extends State<_CoverBackdrop> {
  String? _path;
  String _want = '';

  @override
  void initState() {
    super.initState();
    widget.cover.addListener(_sync);
    _sync();
  }

  @override
  void dispose() {
    widget.cover.removeListener(_sync);
    super.dispose();
  }

  Future<void> _sync() async {
    final url = (widget.cover.value ?? '').trim();
    _want = url;
    if (url.isEmpty) {
      if (mounted) setState(() => _path = null);
      return;
    }
    final p = await CoverStore.path(url);
    if (!mounted || _want != url) return;
    setState(() => _path = p);
  }

  @override
  Widget build(BuildContext context) {
    final dark = Theme.of(context).brightness == Brightness.dark;
    final p = _path;
    return IgnorePointer(
      child: AnimatedSwitcher(
        duration: const Duration(milliseconds: 480),
        child: p == null
            ? const SizedBox.shrink(key: ValueKey('cover-none'))
            : RepaintBoundary(
                key: ValueKey(p),
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    ImageFiltered(
                      imageFilter: ImageFilter.blur(
                        sigmaX: 20,
                        sigmaY: 20,
                        tileMode: TileMode.decal,
                      ),
                      child: Image.file(
                        File(p),
                        fit: BoxFit.cover,
                        cacheWidth: 180,
                        gaplessPlayback: true,
                      ),
                    ),
                    DecoratedBox(
                      decoration: BoxDecoration(
                        gradient: LinearGradient(
                          begin: Alignment.topCenter,
                          end: Alignment.bottomCenter,
                          colors: dark
                              ? [Colors.black.withValues(alpha: 0.58), Colors.black.withValues(alpha: 0.74)]
                              : [Colors.white.withValues(alpha: 0.74), Colors.white.withValues(alpha: 0.86)],
                        ),
                      ),
                    ),
                  ],
                ),
              ),
      ),
    );
  }
}
