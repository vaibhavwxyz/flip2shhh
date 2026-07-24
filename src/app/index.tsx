import { useCallback, useEffect, useState } from 'react';
import {
  ActivityIndicator,
  AppState,
  PermissionsAndroid,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import * as FlipToShhh from '../../modules/flip-to-shhh';

type Phase = 'inactive' | 'active' | 'shushing';

const C = {
  bg: '#07090D',
  card: '#12161F',
  cardBorder: '#1E2530',
  text: '#F5F7FA',
  textDim: '#8A93A3',
  accent: '#7C5CFF',
  accentSoft: 'rgba(124,92,255,0.16)',
  active: '#3DD68C',
  activeSoft: 'rgba(61,214,140,0.14)',
  shush: '#FFB020',
  shushSoft: 'rgba(255,176,32,0.16)',
  danger: '#FF5C5C',
};

export default function HomeScreen() {
  const [dndGranted, setDndGranted] = useState(false);
  const [isRunning, setIsRunning] = useState(false);
  const [isShushing, setIsShushing] = useState(false);
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(() => {
    if (Platform.OS !== 'android') return;
    setDndGranted(FlipToShhh.isDndPermissionGranted());
    setIsRunning(FlipToShhh.isServiceRunning());
    setIsShushing(FlipToShhh.isShushing());
  }, []);

  // Initial state + live updates from the native service.
  useEffect(() => {
    refresh();
    const sub = FlipToShhh.addStatusListener((status) => {
      setIsRunning(status.isRunning);
      setIsShushing(status.isShushing);
    });
    return () => sub.remove();
  }, [refresh]);

  // Re-check permission when returning from system settings.
  useEffect(() => {
    const sub = AppState.addEventListener('change', (state) => {
      if (state === 'active') refresh();
    });
    return () => sub.remove();
  }, [refresh]);

  const requestNotificationsPermission = useCallback(async () => {
    if (Platform.OS !== 'android' || Platform.Version < 33) return;
    try {
      await PermissionsAndroid.request(
        PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS
      );
    } catch {
      // Non-fatal — the foreground-service notification still shows.
    }
  }, []);

  const toggleService = useCallback(async () => {
    if (busy) return;
    setBusy(true);
    try {
      if (isRunning) {
        await FlipToShhh.stopService();
      } else {
        await requestNotificationsPermission();
        await FlipToShhh.startService();
      }
      refresh();
    } finally {
      setBusy(false);
    }
  }, [busy, isRunning, refresh, requestNotificationsPermission]);

  const phase: Phase = isShushing ? 'shushing' : isRunning ? 'active' : 'inactive';

  return (
    <View style={styles.root}>
      <SafeAreaView style={styles.safe} edges={['top', 'bottom']}>
        <View style={styles.header}>
          <Text style={styles.brand}>Flip to Shhh</Text>
          <Text style={styles.tagline}>
            Face down to silence. Flip back to restore.
          </Text>
        </View>

        <StatusOrb phase={phase} />

        {!dndGranted && Platform.OS === 'android' ? (
          <PermissionCard onPress={() => FlipToShhh.openDndSettings()} />
        ) : (
          <View style={styles.permitOk}>
            <Text style={styles.permitOkText}>✓ Do Not Disturb access granted</Text>
          </View>
        )}

        <View style={{ flex: 1 }} />

        <ToggleButton
          isRunning={isRunning}
          busy={busy}
          disabled={!dndGranted && Platform.OS === 'android'}
          onPress={toggleService}
        />

        {Platform.OS !== 'android' && (
          <Text style={styles.platformNote}>
            This app is Android-only — build it on a physical device.
          </Text>
        )}
      </SafeAreaView>
    </View>
  );
}

function StatusOrb({ phase }: { phase: Phase }) {
  const map = {
    inactive: {
      ring: C.cardBorder,
      glow: 'transparent',
      label: 'Inactive',
      sub: 'Service is off',
      icon: '🌙',
    },
    active: {
      ring: C.active,
      glow: C.activeSoft,
      label: 'Watching',
      sub: 'Waiting for face-down',
      icon: '📡',
    },
    shushing: {
      ring: C.shush,
      glow: C.shushSoft,
      label: 'Shhh',
      sub: 'Do Not Disturb is on',
      icon: '🤫',
    },
  }[phase];

  return (
    <View style={styles.orbWrap}>
      <View style={[styles.orbGlow, { backgroundColor: map.glow }]}>
        <View style={[styles.orb, { borderColor: map.ring }]}>
          <Text style={styles.orbIcon}>{map.icon}</Text>
        </View>
      </View>
      <Text style={[styles.orbLabel, { color: map.ring === C.cardBorder ? C.text : map.ring }]}>
        {map.label}
      </Text>
      <Text style={styles.orbSub}>{map.sub}</Text>
    </View>
  );
}

function PermissionCard({ onPress }: { onPress: () => void }) {
  return (
    <Pressable
      onPress={onPress}
      style={({ pressed }) => [styles.permitCard, pressed && styles.pressed]}
    >
      <Text style={styles.permitTitle}>Permission needed</Text>
      <Text style={styles.permitBody}>
        Grant “Do Not Disturb access” so Flip to Shhh can toggle DND for you.
      </Text>
      <View style={styles.permitCta}>
        <Text style={styles.permitCtaText}>Open settings →</Text>
      </View>
    </Pressable>
  );
}

function ToggleButton({
  isRunning,
  busy,
  disabled,
  onPress,
}: {
  isRunning: boolean;
  busy: boolean;
  disabled: boolean;
  onPress: () => void;
}) {
  const bg = isRunning ? 'transparent' : C.accent;
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled || busy}
      style={({ pressed }) => [
        styles.toggle,
        {
          backgroundColor: bg,
          borderColor: isRunning ? C.danger : C.accent,
          opacity: disabled ? 0.4 : 1,
        },
        pressed && !disabled && styles.pressed,
      ]}
    >
      {busy ? (
        <ActivityIndicator color={isRunning ? C.danger : C.text} />
      ) : (
        <Text style={[styles.toggleText, { color: isRunning ? C.danger : C.text }]}>
          {isRunning ? 'Stop service' : 'Start service'}
        </Text>
      )}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: C.bg },
  safe: { flex: 1, paddingHorizontal: 24, paddingBottom: 16 },
  header: { paddingTop: 12, alignItems: 'center', gap: 6 },
  brand: { color: C.text, fontSize: 28, fontWeight: '800', letterSpacing: -0.5 },
  tagline: { color: C.textDim, fontSize: 14, textAlign: 'center' },

  orbWrap: { alignItems: 'center', marginTop: 40, gap: 10 },
  orbGlow: {
    width: 220,
    height: 220,
    borderRadius: 110,
    alignItems: 'center',
    justifyContent: 'center',
  },
  orb: {
    width: 168,
    height: 168,
    borderRadius: 84,
    borderWidth: 3,
    backgroundColor: C.card,
    alignItems: 'center',
    justifyContent: 'center',
  },
  orbIcon: { fontSize: 64 },
  orbLabel: { fontSize: 24, fontWeight: '700', marginTop: 8 },
  orbSub: { color: C.textDim, fontSize: 14 },

  permitCard: {
    marginTop: 32,
    backgroundColor: C.card,
    borderWidth: 1,
    borderColor: C.accent,
    borderRadius: 18,
    padding: 18,
    gap: 8,
  },
  permitTitle: { color: C.text, fontSize: 16, fontWeight: '700' },
  permitBody: { color: C.textDim, fontSize: 14, lineHeight: 20 },
  permitCta: { marginTop: 4 },
  permitCtaText: { color: C.accent, fontSize: 15, fontWeight: '700' },

  permitOk: { marginTop: 32, alignItems: 'center' },
  permitOkText: { color: C.active, fontSize: 14, fontWeight: '600' },

  toggle: {
    height: 60,
    borderRadius: 16,
    borderWidth: 2,
    alignItems: 'center',
    justifyContent: 'center',
  },
  toggleText: { fontSize: 17, fontWeight: '700' },

  pressed: { transform: [{ scale: 0.98 }], opacity: 0.85 },
  platformNote: {
    color: C.textDim,
    fontSize: 12,
    textAlign: 'center',
    marginTop: 12,
  },
});
