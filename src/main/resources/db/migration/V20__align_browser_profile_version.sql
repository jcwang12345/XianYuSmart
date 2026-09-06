-- Keep HTTP requests and browser contexts on the bundled Chromium version from first use.
UPDATE xianyu_device_profile
SET browser_version = '149.0.7827.55'
WHERE browser_version IS NULL OR browser_version = '';
