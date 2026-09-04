import json
import math
import pathlib
import urllib.request


def fetch(url, timeout=30):
    request = urllib.request.Request(
        url,
        headers={"User-Agent": "RadarWallpaper preview builder"},
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read()


metadata = json.loads(fetch("https://api.rainviewer.com/public/weather-maps.json"))
host = metadata["host"]
radar_path = metadata["radar"]["past"][-1]["path"]
latitude, longitude = 25.0330, 121.5654
downloaded = 0

for zoom in (5, 6, 7):
    count = 1 << zoom
    centre_x = int(math.floor((longitude + 180.0) / 360.0 * count))
    sine = math.sin(math.radians(latitude))
    centre_y = int(
        math.floor(
            (0.5 - math.log((1 + sine) / (1 - sine)) / (4 * math.pi))
            * count
        )
    )
    folder = pathlib.Path(f"app/src/main/assets/taiwan_radar/z{zoom}")
    folder.mkdir(parents=True, exist_ok=True)
    for offset_y in range(-5, 6):
        y = centre_y + offset_y
        if y < 0 or y >= count:
            continue
        for offset_x in range(-3, 4):
            x = (centre_x + offset_x) % count
            url = f"{host}{radar_path}/256/{zoom}/{x}/{y}/2/1_0.png"
            try:
                data = fetch(url, timeout=20)
                if data.startswith(b"\x89PNG"):
                    (folder / f"{x}_{y}.png").write_bytes(data)
                    downloaded += 1
            except Exception as error:
                print(f"Skipping {zoom}/{x}/{y}: {error}")

if downloaded == 0:
    raise RuntimeError("RainViewer returned no PNG tiles for the Taipei preview")

print(f"Embedded {downloaded} Taipei radar tiles from {radar_path}")
