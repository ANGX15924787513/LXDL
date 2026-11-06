import 'package:flutter/material.dart';

/// 列表项交错淡入 + 轻微上滑（按 index 递增延迟，最多错开 [maxStagger] 个）。
class StaggeredIn extends StatefulWidget {
  const StaggeredIn({
    super.key,
    required this.index,
    required this.child,
    this.maxStagger = 6,
    this.step = const Duration(milliseconds: 45),
    this.duration = const Duration(milliseconds: 260),
  });

  final int index;
  final Widget child;
  final int maxStagger;
  final Duration step;
  final Duration duration;

  @override
  State<StaggeredIn> createState() => _StaggeredInState();
}

class _StaggeredInState extends State<StaggeredIn> with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: widget.duration,
  );
  late final Animation<double> _fade = CurvedAnimation(parent: _controller, curve: Curves.easeOut);
  late final Animation<Offset> _slide = Tween<Offset>(
    begin: const Offset(0, 0.05),
    end: Offset.zero,
  ).animate(CurvedAnimation(parent: _controller, curve: Curves.easeOutCubic));

  @override
  void initState() {
    super.initState();
    final delay = widget.step * widget.index.clamp(0, widget.maxStagger);
    if (delay == Duration.zero) {
      _controller.forward();
    } else {
      Future.delayed(delay, () {
        if (mounted) _controller.forward();
      });
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return FadeTransition(
      opacity: _fade,
      child: SlideTransition(position: _slide, child: widget.child),
    );
  }
}
