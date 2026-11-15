# 降噪测试语料

两段单声道、22,050 Hz、16 位 PCM 语音由 [eSpeak NG 1.52.0](https://github.com/espeak-ng/espeak-ng/releases/tag/1.52.0) 合成，使用默认语速、音高和音量。

| 文件 | 声音 | 文本 |
| --- | --- | --- |
| `en.wav` | `en` | The quick brown fox jumps over the lazy dog. Testing noise reduction. |
| `cmn.wav` | `cmn` | 今天我们来测试一下声桥的降噪效果，看看风扇和键盘的噪声能不能去掉。 |

测试以这些 WAV 为固定输入，加载后统一重采样到 48 kHz，加上静音和固定随机种子生成的噪声。运行测试无需安装语音合成器。
