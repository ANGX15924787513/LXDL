import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lxdl/src/bridge.dart';

void main() {
  test('Bridge.err 去掉平台异常外壳', () {
    expect(
      Bridge.err(PlatformException(code: 'lxdl', message: '音源不可用：没拿到链接')),
      '音源不可用：没拿到链接',
    );
    expect(Bridge.err(StateError('boom')), contains('boom'));
  });
}
