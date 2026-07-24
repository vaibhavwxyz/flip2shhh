const { withAndroidManifest, AndroidConfig } = require('@expo/config-plugins');

const { withPermissions } = AndroidConfig.Permissions;

/**
 * Expo config plugin for the local `flip-to-shhh` module.
 *
 * During `npx expo prebuild` this injects into AndroidManifest.xml:
 *   1. The permissions the feature needs.
 *   2. The <service> entry for the background FlipService, declared as a
 *      "specialUse" foreground service (required on Android 14+).
 *
 * Keeping this in a config plugin means the generated /android folder stays
 * disposable — everything is reproduced from source on each prebuild.
 */

const FLIP_PERMISSIONS = [
  'android.permission.ACCESS_NOTIFICATION_POLICY',
  'android.permission.FOREGROUND_SERVICE',
  'android.permission.FOREGROUND_SERVICE_SPECIAL_USE',
  'android.permission.VIBRATE',
  'android.permission.POST_NOTIFICATIONS',
];

// Fully-qualified name of the Kotlin service in the local module.
const SERVICE_NAME = 'expo.modules.fliptoshhh.FlipService';

function addFlipService(androidManifest) {
  const application = androidManifest.manifest.application?.[0];
  if (!application) {
    throw new Error(
      'withFlipToShhh: <application> element not found in AndroidManifest.xml'
    );
  }

  application.service = application.service ?? [];

  const alreadyDeclared = application.service.some(
    (service) => service.$?.['android:name'] === SERVICE_NAME
  );

  if (!alreadyDeclared) {
    application.service.push({
      $: {
        'android:name': SERVICE_NAME,
        'android:enabled': 'true',
        'android:exported': 'false',
        'android:foregroundServiceType': 'specialUse',
      },
      // Required declaration for FOREGROUND_SERVICE_SPECIAL_USE on API 34+.
      property: [
        {
          $: {
            'android:name': 'android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE',
            'android:value': 'flip_to_shhh_auto_dnd',
          },
        },
      ],
    });
  }

  return androidManifest;
}

const withFlipToShhh = (config) => {
  config = withPermissions(config, FLIP_PERMISSIONS);
  config = withAndroidManifest(config, (cfg) => {
    cfg.modResults = addFlipService(cfg.modResults);
    return cfg;
  });
  return config;
};

module.exports = withFlipToShhh;
