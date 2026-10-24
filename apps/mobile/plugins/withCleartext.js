const { withAndroidManifest } = require('expo/config-plugins');

/** Allow http://10.0.2.2 (emulator host) in the generated manifest. */
module.exports = function withCleartext(config) {
  return withAndroidManifest(config, (config) => {
    const application = config.modResults.manifest.application?.[0];
    if (application) application.$['android:usesCleartextTraffic'] = 'true';
    return config;
  });
};
