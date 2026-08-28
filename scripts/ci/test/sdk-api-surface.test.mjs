import assert from 'node:assert/strict';
import test from 'node:test';

import { normalizeJavap } from '../sdk-api-surface.mjs';

test('Javap 表面只保留公开与受保护类型成员', () => {
    const output = `Compiled from "Example.java"
public class sample.Example<T> {
  public static final int LIMIT = 3;
    descriptor: I
  protected java.util.List<T> values();
    descriptor: ()Ljava/util/List;
  static {};
    descriptor: ()V
}
final class sample.Internal {
  public void hiddenWithItsType();
    descriptor: ()V
}`;
    assert.deepEqual(normalizeJavap('plugin-api', output), [
        'plugin-api\tMEMBER\tpublic class sample.Example<T>\tprotected java.util.List<T> values();\tdescriptor: ()Ljava/util/List;',
        'plugin-api\tMEMBER\tpublic class sample.Example<T>\tpublic static final int LIMIT = 3;\tdescriptor: I',
        'plugin-api\tTYPE\tpublic class sample.Example<T>'
    ]);
});
