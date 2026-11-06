import 'package:flutter/material.dart';
import 'bridge.dart';
import 'pages/search.dart';
import 'pages/downloads.dart';
import 'pages/sources.dart';

class LxdlApp extends StatefulWidget {
  const LxdlApp({super.key});
  @override
  State<LxdlApp> createState() => _LxdlAppState();
}

class _LxdlAppState extends State<LxdlApp> {
  int _tab = 0;
  final ValueNotifier<int> _tick = ValueNotifier(0);
  final List<String> _logs = [];
  final GlobalKey<ScaffoldMessengerState> _scaffold = GlobalKey();
  int _lastToast = 0;

  @override
  void initState() {
    super.initState();
    Bridge.listen();
    Bridge.onEvent = (type, data) {
      if (type == 'downloads' || type == 'progress') {
        _tick.value++;
      } else if (type == 'log') {
        final text = data['text'] as String? ?? '';
        if (text.isEmpty) return;
        _logs.add(text);
        if (_logs.length > 200) _logs.removeAt(0);
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
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      scaffoldMessengerKey: _scaffold,
      debugShowCheckedModeBanner: false,
      title: '洛雪下载器',
      theme: ThemeData(
        useMaterial3: true,
        colorSchemeSeed: const Color(0xFF3A7AF0),
        visualDensity: VisualDensity.compact,
      ),
      home: Builder(
        builder: (context) => Scaffold(
          body: SafeArea(
            child: IndexedStack(
              index: _tab,
              children: [
                SearchPage(tick: _tick, onStarted: () => setState(() => _tab = 1)),
                DownloadsPage(tick: _tick),
                const SourcesPage(),
              ],
            ),
          ),
          bottomNavigationBar: NavigationBar(
            height: 62,
            selectedIndex: _tab,
            labelBehavior: NavigationDestinationLabelBehavior.alwaysHide,
            onDestinationSelected: (i) => setState(() => _tab = i),
            destinations: const [
              NavigationDestination(icon: Icon(Icons.search), label: '搜索'),
              NavigationDestination(icon: Icon(Icons.download), label: '下载'),
              NavigationDestination(icon: Icon(Icons.cloud), label: '音源'),
            ],
          ),
        ),
      ),
    );
  }
}
