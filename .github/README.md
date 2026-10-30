# Hollowhaven Clip Forge + Wan 2.2 Animate 2

This build adds a real server-side WaveSpeedAI bridge to the Clip Forge UI.

## Flow

Browser -> Clip Forge server -> WaveSpeed media upload -> Wan 2.2 Animate 2 -> prediction polling -> result

The WaveSpeed API key is read only from `WAVESPEED_API_KEY` on the server. It is never placed in `index.html` or sent to the browser.

## Run

1. Install Node.js 20+.
2. Run `npm install`.
3. Set `WAVESPEED_API_KEY` in the environment. Do not paste a real key into source files.
4. Run `npm start`.
5. Open `http://localhost:8787`.

The UI will let you select a Remnant reference image, a driving video, resolution, prompt and motion prompt, then submit the real Wan 2.2 Animate 2 task.

## Important

WaveSpeed task submission is intentionally not automatically retried because an ambiguous submission response can represent an already-billed task. Result polling is safe to retry. Generated media URLs are temporary, so download outputs you want to keep.
