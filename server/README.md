# Aniart VR PC Processing

Домашний сервер для конечных HTTP(S)-источников, включая MP4 и HLS VOD. Quest выбирает серию и передаёт ссылку и её HTTP-заголовки. ПК прогоняет Anime4K CNN x2 S через FFmpeg libplacebo/Vulkan, считает глубину через Limbo V3 и отдаёт H.264/AAC в MPEG-TS. Для режима «Параллакс» поток содержит исходный ракурс и синхронную карту глубины в полосе снизу кадра. Плеер использует её как маску для трёх плоскостей. Режим SBS рассчитывает ракурсы на ПК. Выход рассчитан на один Quest.

## Установка на Linux

1. Установите драйвер и PyTorch с ROCm по инструкции AMD для вашей системы и **RX 9060 XT / gfx1200**. Установите также совместимую версию torchvision. `torch.cuda` — имя API PyTorch для GPU, в том числе HIP на AMD. [Матрица AMD](https://rocm.docs.amd.com/projects/radeon-ryzen/en/latest/docs/compatibility/compatibilityrad/native_linux/native_linux_compatibility.html)
2. Нужен FFmpeg с фильтром `libplacebo` и драйвер Vulkan. Для аппаратного кодирования нужен `h264_vaapi` и устройство Mesa VAAPI. Проверьте `ffmpeg -filters`, `ffmpeg -encoders`, `vulkaninfo --summary` и `vainfo --display drm --device /dev/dri/renderD128`.
3. Используйте Python 3.12. Зафиксированный коммит DA3 поддерживает Python до 3.13 включительно. На Manjaro с системным Python 3.14 можно создать отдельное окружение через uv.

```sh
uv venv --seed --python 3.12 .venv-rocm
source .venv-rocm/bin/activate
python -m pip install 'torch==2.14.1+rocm7.2' 'torchvision==0.29.1+rocm7.2' --index-url https://download.pytorch.org/whl/rocm7.2
```

Запустите команды из корня репозитория.

```sh
python -m pip install -r server/requirements.txt
python -m pip install --no-deps 'e3nn==0.6.0' 'opt-einsum-fx==0.1.4'
python -m pip install --no-deps 'git+https://github.com/ByteDance-Seed/Depth-Anything-3.git@3d835ec1a5802d64a8b8b15f817a1ab54809bfe4'
mkdir -p models
curl -fL 'https://raw.githubusercontent.com/NevermindNilas/Ai-models/4c0950ecc08629cda85754d0766d6deb2e95495a/Limbo-V3/Limbo.safetensors' -o models/Limbo.safetensors
echo '8aa1dccf97bd7f1bca7d051ffa983dfe6faeed2356ba5eb9db63808d49f036f3  models/Limbo.safetensors' | sha256sum -c -
```

Если терминал уже открыт в папке `server`, используйте `requirements.txt` вместо `server/requirements.txt`. Файл устанавливает обычные Python-библиотеки. Пакеты `e3nn` и `opt-einsum-fx` устанавливаются отдельно с `--no-deps`, чтобы использовать уже установленный PyTorch с ROCm. После установки проверьте `python -m pip check` и `python -c 'import torch; print(torch.version.hip, torch.cuda.is_available())'`.

Limbo V3 от NevermindNilas выбрана первой для аниме. Оригинальные веса CC BY-NC 4.0 происходят от DA3 Small. Пользовательский сервер рассчитан на некоммерческий домашний просмотр. [Модель и лицензия](https://github.com/NevermindNilas/Ai-models/tree/4c0950ecc08629cda85754d0766d6deb2e95495a/Limbo-V3)

## Запуск и подключение Quest

Подставьте LAN-адрес ПК и устройство VAAPI. Если Vulkan выбрал другое устройство, задайте его индекс через `--vulkan-device`.

```sh
PC_IP=192.168.1.50
export ANIART_PC_TOKEN="$(python -c 'import secrets; print(secrets.token_urlsafe(24))')"
printf 'http://%s:8765#%s\n' "$PC_IP" "$ANIART_PC_TOKEN"
python server/aniart_server.py --host "$PC_IP" --checkpoint models/Limbo.safetensors \
  --encode-device /dev/dri/renderD128
```

Скопируйте выведенный адрес в буфер Quest и нажмите **Настройки → ПК: выкл.** Кнопка переключится на **ПК: вкл.** Затем откройте обычную 2D-серию из каталога. В версии 0.8.1 по умолчанию включён **Параллакс**. В настройках во время просмотра можно менять отступ слоёв или выбрать **SBS**. Повторное нажатие на «ПК» отключает обработку на ПК для следующей серии. Используйте доверенную домашнюю сеть. Сервер принимает исходные URL и заголовки от клиента с токеном, а HTTP в этой debug-сборке не шифруется.

По умолчанию выход **720p, 24 FPS**, глубина 504×280 раз в два кадра. SBS кодируется в 2560×720. Для источников 8-bit H.264 можно отдельно попробовать аппаратный декодер `--decode-device /dev/dri/renderD128`. Для 4K SBS попробуйте `--width 3840 --height 2160 --half-sbs --bitrate 28M`. Quest получит единый кадр 3840×2160, по 1920×2160 на глаз с SAR 2:1. Это тестовый режим без замера на RX 9060 XT.

Для отладки можно убрать `--encode-device` и использовать libx264, отключить Vulkan через `--no-anime4k` или выбрать `--device cpu`. Сервер пишет время до первой выдачи и среднюю скорость обработки. Текущая версия рассчитана на материал 16:9 и отдаёт первую звуковую дорожку. Когда ffprobe видит длительность эпизода, работают шкала, шаг ±10 секунд и продолжение просмотра. При перемотке сервер начинает новое кодирование с выбранной отметки.

## Запуск без PyTorch и ROCm

Готовый Limbo ONNX запускается на CPU через ONNX Runtime. Anime4K через Vulkan и VAAPI-кодирование по-прежнему можно использовать на Radeon. Этот режим подходит для проверки связи Quest с сервером. Скорость 24 FPS с глубиной на Ryzen 5 5600 пока не измерена.

Команды из папки `server` в Python 3.12:

```sh
python -m pip install onnxruntime==1.24.3 numpy opencv-python-headless
mkdir -p models
curl -fL 'https://raw.githubusercontent.com/NevermindNilas/Ai-models/4c0950ecc08629cda85754d0766d6deb2e95495a/Limbo-V3/Limbo_504x280_fp32.onnx' -o models/Limbo.onnx
printf '%s  %s\n' '6a8f894f08fdb651036c3e6875bf357574b03003a4da2ae1ca9e82b66fc8534f' models/Limbo.onnx | sha256sum -c -
python aniart_server.py --host "$PC_IP" --checkpoint models/Limbo.onnx --depth-every 6 --encode-device /dev/dri/renderD128
```

Задайте `PC_IP` и `ANIART_PC_TOKEN` как в разделе запуска. ONNX фиксирован на 504×280. Отключите `--encode-device`, если кодирование VAAPI ещё не настроено.

### Глубина на Radeon через WebGPU без ROCm

Экспериментальный режим. Python 3.12 использует ONNX Runtime и загружаемый
провайдер WebGPU. На Linux провайдер выполняет операции через Vulkan. Сервер
запрещает CPU fallback и проверяет конечность карты при запуске. Если GPU
не поддерживает нужный оператор, сервер остановится с ошибкой ONNX Runtime.

```sh
python -m pip install 'onnxruntime==1.27.0' 'onnxruntime-ep-webgpu==0.6.0'
curl -fL 'https://raw.githubusercontent.com/NevermindNilas/Ai-models/4c0950ecc08629cda85754d0766d6deb2e95495a/Limbo-V3/Limbo_504x280_fp16.onnx' -o models/Limbo-fp16.onnx
printf '%s  %s\n' '19e1de53564bc1c84e47acbf98897e7ceb2db2e607661e3bf0997cd7272d7482' models/Limbo-fp16.onnx | sha256sum -c -
ANIART_DEPTH_SWEEP=1 ANIART_BENCH_DEPTH_DEVICE=webgpu bash bench_bunny.sh models/Limbo-fp16.onnx
```

Режим рабочей сессии выбирается флагом `--device webgpu`. Тот же флаг принимает
исходный FP32 ONNX для сравнения. Anime4K и VAAPI используют прежние настройки.

Для сравнения частоты карт глубины запустите из папки `server`:

```sh
ANIART_DEPTH_SWEEP=1 ANIART_BENCH_DEPTH_DEVICE=webgpu ANIART_DEPTH_PREVIEW=1 bash bench_bunny.sh models/Limbo-fp16.onnx
```

Скрипт проверяет глубину раз в 6, 3, 2 кадра и на каждом кадре. В `summary.txt`
записываются число карт, скорость обработки, время на карту и время стереосдвига.
В `video/*-depth.mp4` слева исходный кадр, справа нормализованная карта,
которую использует стерео. Белый соответствует ближним областям. При
пропуске пересчёта справа повторяется предыдущая карта. Кодирование
этого превью выполняется после замера.

### Уточнение контуров в 720p

Флаг сервера `--depth-refine` уточняет карту по яркости кадра в разрешении
глаза. Вход Limbo 504×280 соответствует разрешению режима `Low` в тесте
TheAnimeScripter. В том же тесте `High` рассчитывает глубину в исходном
разрешении 1280×720. [Сравнение режимов Limbo V3](https://github.com/NevermindNilas/Ai-models/tree/main/Limbo-V3#video-stability)

Для сравнения линейного масштабирования и уточнения на каждом кадре запустите:

```sh
ANIART_DEPTH_REFINE_SWEEP=1 ANIART_DEPTH_PREVIEW=1 bash bench_bunny.sh models/Limbo-fp16.onnx
```

Оба прогона используют WebGPU, Anime4K и VAAPI. Вместо уменьшенного превью
сохраняется сравнение исходного кадра и карты в 1280×720 на панель.

Добавьте `ANIART_WEBGPU_CAPTURE_SWEEP=1`, чтобы отдельно проверить тот же
режим с повторным использованием записанных GPU-команд ONNX Runtime.
Сервер проверяет, что результаты меняются при смене кадра и воспроизводятся
при возврате к исходному кадру. Если WebGPU или модель не поддержат захват
графа, ошибка будет в логе только этого прогона.

### Параллельная обработка кадров

С `--depth-pipeline` сервер читает следующий кадр и рассчитывает его глубину,
пока текущий проходит уточнение, стереосдвиг и передачу в кодировщик.
Стерео использует карты по порядку, а в очереди находится максимум один кадр.
Сравнение на Radeon с картой на каждом кадре и уточнением в 720p:

```sh
ANIART_DEPTH_PIPELINE_SWEEP=1 bash bench_bunny.sh models/Limbo-fp16.onnx
```

Профили `depth-720p-refined` и `depth-720p-pipelined` используют Anime4K,
WebGPU и VAAPI. В `summary.txt` есть общий FPS, время первого байта и
отдельные замеры расчёта карты, её обработки и стереосдвига.

Чтобы повторить сравнение на своём фрагменте аниме, укажите локальный файл.
Для оценки поведения после прогрева подойдёт фрагмент на 30–60 секунд.

```sh
ANIART_BENCH_SOURCE="$PWD/clip.mp4" ANIART_DEPTH_PIPELINE_SWEEP=1 bash bench_bunny.sh models/Limbo-fp16.onnx
```

Во время каждого прогона скрипт раз в секунду пишет доступные показатели
выбранного DRM-устройства в `logs/*.gpu.csv`. Температура записывается
в тысячных долях °C, мощность в мкВт, частота в Гц и загрузка в процентах.
Архив `logs.tar.gz` включает CSV и замеры производительности. Видеофайлы
сохраняются отдельно.

### Сравнение Low, Medium и High

Скачайте артефакт `Limbo-V3-PC-quality` из успешного запуска GitHub Actions
и распакуйте два файла ONNX в папку `server/models` рядом с
`Limbo-fp16.onnx`. [Экспорт моделей для ПК](https://github.com/ytpk/aniart-vr/actions/workflows/limbo-pc-quality.yml)

Из папки `server` запустите:

```sh
ANIART_DEPTH_QUALITY_SWEEP=1 bash bench_bunny.sh models/Limbo-fp16.onnx
```

Профили используют входы Limbo V3 FP16 504×280 (Low), 700×392 (Medium) и
1274×714 (High). Размеры кратны шагу модели в 14 пикселей. У всех профилей
включены WebGPU, Anime4K, VAAPI, карта на каждом кадре, уточнение контуров
и конвейер. Выход каждого профиля составляет 1280×720 на глаз. Для видеопревью
карт добавьте `ANIART_DEPTH_PREVIEW=1`.
