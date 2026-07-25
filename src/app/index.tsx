import { useCallback, useEffect, useState } from 'react';
import {
  ActivityIndicator,
  AppState,
  PermissionsAndroid,
  Platform,
  Pressable,
  ScrollView,
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
  const [batteryOk, setBatteryOk] = useState(false);
  const [isRunning, setIsRunning] = useState(false);
  const [isShushing, setIsShushing] = useState(false);
  const [busy, setBusy] = useState(false);

  // OEM auto-start is a manual, un-queryable step — track device support once
  // and whether the user has confirmed they completed it.
  const [hasAutoStart, setHasAutoStart] = useState(false);
  const [manufacturer, setManufacturer] = useState('');
  const [autoStartAck, setAutoStartAck] = useState(false);

  const refresh = useCallback(() => {
    if (Platform.OS !== 'android') return;
    setDndGranted(FlipToShhh.isDndPermissionGranted());
    setBatteryOk(FlipToShhh.isIgnoringBatteryOptimizations());
    setIsRunning(FlipToShhh.isServiceRunning());
    setIsShushing(FlipToShhh.isShushing());
  }, []);

  // Device capabilities that don't change at runtime.
  useEffect(() => {
    if (Platform.OS !== 'android') return;
    setHasAutoStart(FlipToShhh.hasAutoStartSettings());
    setManufacturer(FlipToShhh.getManufacturer());
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

  // Battery exemption is required (not just recommended) so users can't leave
  // the service in a state OEM battery managers will silently kill. Where an
  // OEM auto-start screen exists, we also require the user to confirm that
  // manual, un-queryable step.
  const oemStepDone = !hasAutoStart || autoStartAck;
  const requirementsMet = dndGranted && batteryOk && oemStepDone;
  const startBlocked = Platform.OS === 'android' && !isRunning && !requirementsMet;
  const gateHint = !dndGranted
    ? 'Grant Do Not Disturb access to continue.'
    : !batteryOk
      ? 'Allow unrestricted battery to continue.'
      : 'Confirm the auto-start step to continue.';

  return (
    <View style={styles.root}>
      <SafeAreaView style={styles.safe} edges={['top', 'bottom']}>
        <View style={styles.header}>
          <Text style={styles.brand}>Flip to Shhh</Text>
          <Text style={styles.tagline}>
            Face down to silence. Flip back to restore.
          </Text>
        </View>

        <ScrollView
          style={styles.scroll}
          contentContainerStyle={styles.scrollContent}
          showsVerticalScrollIndicator={false}
        >
          <StatusOrb phase={phase} />

          {Platform.OS === 'android' && (
            <View style={styles.reqCard}>
              <CheckRow
                label="Do Not Disturb access"
                detail="Required to toggle DND"
                ok={dndGranted}
                onFix={() => FlipToShhh.openDndSettings()}
              />
              <View style={styles.reqDivider} />
              <CheckRow
                label="Unrestricted battery"
                detail="Required — keeps it alive after swipe-away"
                ok={batteryOk}
                onFix={() => FlipToShhh.requestIgnoreBatteryOptimizations()}
              />
            </View>
          )}

          {Platform.OS === 'android' && hasAutoStart && (
            <OemCard
              manufacturer={manufacturer}
              acked={autoStartAck}
              onOpen={() => FlipToShhh.openAutoStartSettings()}
              onToggleAck={() => setAutoStartAck((v) => !v)}
            />
          )}
        </ScrollView>

        <View style={styles.footer}>
          <ToggleButton
            isRunning={isRunning}
            busy={busy}
            disabled={startBlocked}
            onPress={toggleService}
          />
          {startBlocked && <Text style={styles.footerHint}>{gateHint}</Text>}
          {Platform.OS !== 'android' && (
            <Text style={styles.platformNote}>
              This app is Android-only — build it on a physical device.
            </Text>
          )}
        </View>
      </SafeAreaView>
    </View>
  );
}

function StatusOrb({ phase }: { phase: Phase }) {
  const map = {
    inactive: {
      ring: C.cardBorder,
      label: 'Inactive',
      sub: 'Service is off',
      icon: '🌙',
    },
    active: {
      ring: C.active,
      label: 'Watching',
      sub: 'Waiting for face-down',
      icon: '📡',
    },
    shushing: {
      ring: C.shush,
      label: 'Shhh',
      sub: 'Do Not Disturb is on',
      icon: '🤫',
    },
  }[phase];

  return (
    <View style={styles.orbWrap}>
      <View style={styles.orbGlow}>
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

function CheckRow({
  label,
  detail,
  ok,
  onFix,
}: {
  label: string;
  detail: string;
  ok: boolean;
  onFix: () => void;
}) {
  return (
    <View style={styles.checkRow}>
      <View
        style={[
          styles.checkBadge,
          { backgroundColor: ok ? C.activeSoft : C.shushSoft },
        ]}
      >
        <Text style={{ color: ok ? C.active : C.shush, fontWeight: '800' }}>
          {ok ? '✓' : '!'}
        </Text>
      </View>
      <View style={{ flex: 1 }}>
        <Text style={styles.checkLabel}>{label}</Text>
        <Text style={styles.checkDetail}>{detail}</Text>
      </View>
      {ok ? (
        <Text style={styles.checkOk}>Granted</Text>
      ) : (
        <Pressable onPress={onFix} hitSlop={8}>
          <Text style={styles.checkFix}>Fix →</Text>
        </Pressable>
      )}
    </View>
  );
}

function OemCard({
  manufacturer,
  acked,
  onOpen,
  onToggleAck,
}: {
  manufacturer: string;
  acked: boolean;
  onOpen: () => void;
  onToggleAck: () => void;
}) {
  const brand =
    manufacturer && manufacturer.length > 1
      ? manufacturer.charAt(0).toUpperCase() + manufacturer.slice(1).toLowerCase()
      : 'Your device';
  return (
    <View style={styles.oemCard}>
      <Text style={styles.oemTitle}>⚠️ One more step on {brand}</Text>
      <Text style={styles.oemBody}>
        {brand} can still close background apps even with battery unrestricted.
        Open Auto-start (a.k.a. “Allow background activity” / “Don’t optimize”)
        and enable it for Flip to Shhh.
      </Text>

      <Pressable
        onPress={onOpen}
        style={({ pressed }) => [styles.oemBtn, pressed && styles.pressed]}
      >
        <Text style={styles.oemBtnText}>Open Auto-start settings →</Text>
      </Pressable>

      <Pressable onPress={onToggleAck} style={styles.ackRow} hitSlop={8}>
        <View style={[styles.ackBox, acked && styles.ackBoxOn]}>
          {acked && <Text style={styles.ackTick}>✓</Text>}
        </View>
        <Text style={styles.ackText}>I’ve enabled Auto-start for this app</Text>
      </Pressable>
    </View>
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

  reqCard: {
    marginTop: 32,
    backgroundColor: C.card,
    borderWidth: 1,
    borderColor: C.cardBorder,
    borderRadius: 18,
    paddingHorizontal: 16,
  },
  reqDivider: { height: 1, backgroundColor: C.cardBorder },
  checkRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    paddingVertical: 14,
  },
  checkBadge: {
    width: 28,
    height: 28,
    borderRadius: 14,
    alignItems: 'center',
    justifyContent: 'center',
  },
  checkLabel: { color: C.text, fontSize: 15, fontWeight: '600' },
  checkDetail: { color: C.textDim, fontSize: 12, marginTop: 1 },
  checkOk: { color: C.active, fontSize: 13, fontWeight: '700' },
  checkFix: { color: C.accent, fontSize: 15, fontWeight: '700' },

  scroll: { flex: 1, alignSelf: 'stretch' },
  scrollContent: { paddingBottom: 12 },

  oemCard: {
    marginTop: 16,
    backgroundColor: C.card,
    borderWidth: 1,
    borderColor: C.shush,
    borderRadius: 18,
    padding: 16,
    gap: 12,
  },
  oemTitle: { color: C.text, fontSize: 15, fontWeight: '700' },
  oemBody: { color: C.textDim, fontSize: 13, lineHeight: 19 },
  oemBtn: {
    backgroundColor: C.shushSoft,
    borderRadius: 12,
    paddingVertical: 12,
    alignItems: 'center',
  },
  oemBtnText: { color: C.shush, fontSize: 14, fontWeight: '700' },
  ackRow: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  ackBox: {
    width: 22,
    height: 22,
    borderRadius: 6,
    borderWidth: 2,
    borderColor: C.cardBorder,
    alignItems: 'center',
    justifyContent: 'center',
  },
  ackBoxOn: { backgroundColor: C.active, borderColor: C.active },
  ackTick: { color: '#07090D', fontSize: 13, fontWeight: '900' },
  ackText: { color: C.text, fontSize: 13, flex: 1 },

  footer: { paddingTop: 12, gap: 8 },
  footerHint: { color: C.textDim, fontSize: 12, textAlign: 'center' },

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
