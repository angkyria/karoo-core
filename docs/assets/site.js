/*
 * CORE Heat's project page.
 *
 * Every Karoo field on this page is drawn here, by a port of the extension's own renderer and
 * heat model (core/src/main/kotlin/io/angkyria/coreheat), in its own font and at a Karoo 2's own
 * pixel sizes -- so the page shows what the Karoo draws rather than an illustration of it. Names
 * follow the Kotlin's, so either side can be found from the other. Where the two disagree, the
 * Kotlin is right and this is stale.
 */
(() => {
  'use strict';

  const clamp = (v, lo, hi) => Math.min(Math.max(v, lo), hi);

  // ---------------------------------------------------------------------------------------------
  // The heat model: HeatStrain, HeatAdaptation and the HUD's load steps.

  // HeatStrain: core temperature (°C) at which the index reaches 1.0, 3.0 and 7.0, one entry per
  // half degree of skin temperature from 28.0 to 38.0 -- CORE's zone chart, read off by hand.
  const HSI_1 = [
    38.80, 38.73, 38.67, 38.61, 38.54, 38.48, 38.41, 38.34, 38.26, 38.19, 38.10,
    38.01, 37.92, 37.81, 37.70, 37.59, 37.46, 37.33, 37.19, 37.04, 36.88,
  ];
  const HSI_3 = [
    39.62, 39.60, 39.56, 39.51, 39.43, 39.35, 39.25, 39.14, 39.02, 38.90, 38.77,
    38.63, 38.51, 38.37, 38.24, 38.11, 37.98, 37.85, 37.72, 37.61, 37.49,
  ];
  const HSI_7 = [
    40.00, 40.00, 40.00, 40.00, 39.99, 39.97, 39.95, 39.93, 39.90, 39.86, 39.82,
    39.77, 39.72, 39.65, 39.58, 39.50, 39.41, 39.31, 39.21, 39.10, 38.98,
  ];
  const SKIN_MIN = 28.0;
  const SKIN_STEP = 0.5;
  const SLOPE_ABOVE_7 = 3.0 / (40.0 - 39.21);

  function rowValue(table, row) {
    const lo = Math.min(Math.trunc(row), table.length - 2);
    return table[lo] + (table[lo + 1] - table[lo]) * (row - lo);
  }

  /** The three zone boundaries on [skin], skin outside the chart read on its nearest edge row. */
  function boundaries(skin) {
    const row = clamp((skin - SKIN_MIN) / SKIN_STEP, 0, HSI_1.length - 1);
    return [rowValue(HSI_1, row), rowValue(HSI_3, row), rowValue(HSI_7, row)];
  }

  /** HeatStrain.index: the estimate a field shows until it has the sensor's own index. */
  function heatStrainIndex(core, skin) {
    if (!Number.isFinite(core) || !Number.isFinite(skin)) return null;
    if (core < 30 || core > 45 || skin < 15 || skin > 45) return null;
    const [t1, t3, t7] = boundaries(skin);
    let hsi;
    if (core < t1) hsi = 1 - 2 * (t1 - core) / (t3 - t1);
    else if (core < t3) hsi = 1 + 2 * (core - t1) / (t3 - t1);
    else if (core < t7) hsi = 3 + 4 * (core - t3) / (t7 - t3);
    else hsi = 7 + SLOPE_ABOVE_7 * (core - t7);
    return clamp(hsi, 0, 10);
  }

  /** The core temperature at which [heatStrainIndex] reaches [hsi] on [skin]: its inverse. */
  function coreAt(hsi, skin) {
    const [t1, t3, t7] = boundaries(skin);
    if (hsi < 1) return t1 - (1 - hsi) * (t3 - t1) / 2;
    if (hsi < 3) return t1 + (hsi - 1) * (t3 - t1) / 2;
    if (hsi < 7) return t3 + (hsi - 3) * (t7 - t3) / 4;
    return t7 + (hsi - 7) / SLOPE_ABOVE_7;
  }

  /** A value rounded to the tenth a field prints: zones and levels are decided on this. */
  const tenth = (v) => Math.round(v * 10) / 10;

  function heatZone(hsi) {
    const shown = tenth(hsi);
    return shown < 1 ? 1 : shown < 3 ? 2 : shown < 7 ? 3 : 4;
  }

  const ZONE_COLORS = ['#37CA94', '#FFC655', '#FFA06A', '#F35264'];
  const ZONE_NAMES = ['no heat strain', 'moderate heat strain', 'high heat strain', 'extremely high heat strain'];
  const LEVEL_FLOORS = [0, 25, 50, 90];
  const LEVEL_COLORS = ['#A2DBED', '#73C9E4', '#45B8DB', '#1C8DB5'];
  const LOAD_STEPS = [2, 4, 6, 8];
  const LOAD_COLORS = ['#B794F6', '#9F7AEA', '#805AD5', '#6B46C1'];

  /** HeatAdaptation.level, as an index into the four levels. */
  function adaptationLevel(score) {
    const shown = tenth(score);
    let level = 0;
    LEVEL_FLOORS.forEach((floor, i) => { if (shown >= floor) level = i; });
    return level;
  }

  const zoneColor = (hsi) => (hsi == null ? null : ZONE_COLORS[heatZone(hsi) - 1]);

  // ---------------------------------------------------------------------------------------------
  // Formatters and RaisedTail.

  const fixed1 = (v) => tenth(v).toFixed(1);

  const format = {
    temperature: (celsius, fahrenheit) => fixed1(fahrenheit ? celsius * 9 / 5 + 32 : celsius),
    tenths: (v) => fixed1(v),
    count: (v) => String(Math.trunc(v)),
    // One decimal while it fits the "00.0" budget, none past it: "99.9", then "100".
    percent: (v) => (v <= -10 || Math.round(v * 10) >= 1000 ? String(Math.round(v)) : fixed1(v)),
  };

  function splitTail(text, raised) {
    if (!raised) return [text, ''];
    const i = text.lastIndexOf('.');
    return i <= 0 ? [text, ''] : [text.slice(0, i), text.slice(i + 1)];
  }

  function splitTemplate(template, raised) {
    return raised && template.includes('.') ? [template.replace('.', ''), ''] : [template, ''];
  }

  // ---------------------------------------------------------------------------------------------
  // FieldRenderer's measurements, worked out for a Karoo 2: 480x800 at 300 dpi.

  const DENSITY = 1.875;

  /** Resources.getDimensionPixelSize: rounded to the nearest pixel, and never 0 for a non-zero. */
  function dimensionPixelSize(dp) {
    const f = dp * DENSITY;
    const px = Math.trunc(f >= 0 ? f + 0.5 : f - 0.5);
    return px !== 0 ? px : dp === 0 ? 0 : dp > 0 ? 1 : -1;
  }

  const PAD = dimensionPixelSize(5); // field_edge_padding
  const LABEL_HEIGHT = 11.07 * DENSITY;
  const ICON_SIZE = Math.trunc(LABEL_HEIGHT * 1.4);
  const ICON_GAP = 3 * DENSITY;
  const LABEL_BAND = Math.max(ICON_SIZE, LABEL_HEIGHT);
  const HEADER_TOP = Math.max(PAD - 2, 0);
  const HEADER_HEIGHT = Math.trunc(LABEL_BAND + HEADER_TOP + dimensionPixelSize(6));
  const VALUE_BOTTOM = Math.max(PAD - 5, 0);
  const CARD_RADIUS = 10 * DENSITY;
  const DIVIDER = 1; // hud_divider_width, in px on every screen
  const PILL_HEIGHT = Math.trunc(LABEL_BAND) + 2; // the label band and hud_pill_inset above and below
  const LABEL_CENTRE = HEADER_TOP + LABEL_BAND / 2;
  const TEXT_SIZE = 200;
  const DIGITS = '0123456789';
  const ICON_GREEN = '#10B981';
  const TEMPLATE = '00.0';

  const FAMILY = '"DIN 1451 Mittelschrift"';
  const fontAt = (size) => `${size}px ${FAMILY}`;
  const measurer = document.createElement('canvas').getContext('2d');

  // Every tile is drawn in Karoo pixels, whatever size it is shown at, so the same few strings
  // come back at the same few sizes on every repaint: each is measured once. Cleared once the
  // face has loaded, and should it ever pass a size no visit to the page comes near.
  const measured = new Map();
  function measure(text, size) {
    const key = `${size}|${text}`;
    let m = measured.get(key);
    if (!m) {
      if (measured.size > 4000) measured.clear();
      measurer.font = fontAt(size);
      const tm = measurer.measureText(text);
      m = { width: tm.width, ascent: tm.actualBoundingBoxAscent, descent: tm.actualBoundingBoxDescent };
      measured.set(key, m);
    }
    return m;
  }

  const advance = (text, size) => measure(text, size).width;

  /** Paint.getTextBounds: ink bounds about the baseline, rounded outwards to whole pixels. */
  function inkBounds(text, size) {
    const m = measure(text, size);
    const top = Math.floor(-m.ascent);
    const bottom = Math.ceil(m.descent);
    return { top, height: bottom - top };
  }

  const baselineFor = (boxHeight, inkHeight, inkTop) => (boxHeight - inkHeight) / 2 - inkTop;

  /** ZoneColors.onColor: black or white, whichever reads on [hex] by WCAG relative luminance. */
  function onColor(hex) {
    const n = parseInt(hex.slice(1), 16);
    const channel = (shift) => {
      const c = ((n >> shift) & 0xff) / 255;
      return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    };
    const luminance = 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0);
    return luminance > 0.179 ? '#000000' : '#FFFFFF';
  }

  const inkFor = (night) => (night ? '#FFFFFF' : '#000000');
  const cardFor = (night) => (night ? '#000000' : '#FFFFFF');

  function roundRect(ctx, x, y, w, h, r) {
    r = Math.min(r, w / 2, h / 2);
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.arcTo(x + w, y, x + w, y + h, r);
    ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r);
    ctx.arcTo(x, y, x + w, y, r);
    ctx.closePath();
  }

  function fillRoundRect(ctx, x, y, w, h, r, color) {
    roundRect(ctx, x, y, w, h, r);
    ctx.fillStyle = color;
    ctx.fill();
  }

  // ic_temp, the thermometer every header and pill carries.
  const THERMOMETER = new Path2D(
    'M15,13V5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v8c-1.21,0.91 -2,2.37 -2,4 0,2.76 2.24,5 5,5' +
    's5,-2.24 5,-5c0,-1.63 -0.79,-3.09 -2,-4zM11,5c0,-0.55 0.45,-1 1,-1s1,0.45 1,1h-1v1h1v2h-1v1h1v2h-2V5z',
  );

  function drawThermometer(ctx, x, y, size, color) {
    ctx.save();
    ctx.translate(x, y);
    ctx.scale(size / 24, size / 24);
    ctx.fillStyle = color;
    ctx.fill(THERMOMETER);
    ctx.restore();
  }

  // Measured on a capital, so every label's capitals come out LABEL_HEIGHT tall whatever it says.
  let labelSize = null;
  function labelTextSize() {
    if (labelSize == null) {
      const cap = inkBounds('H', LABEL_HEIGHT).height;
      labelSize = cap > 0 ? LABEL_HEIGHT * LABEL_HEIGHT / cap : LABEL_HEIGHT;
    }
    return labelSize;
  }

  function headerWidth(label, withIcon) {
    const icon = withIcon ? ICON_SIZE + ICON_GAP : 0;
    return Math.trunc(icon + advance(label.toUpperCase(), labelTextSize()) + 2 * PAD);
  }

  /** FieldRenderer.renderHeader, drawn straight onto the tile at [x] instead of into a bitmap. */
  function drawHeader(ctx, x, label, labelColor, iconColor, withIcon) {
    const size = labelTextSize();
    const cap = inkBounds('H', size);
    const iconSize = withIcon ? ICON_SIZE : 0;
    const iconGap = withIcon ? ICON_GAP : 0;
    if (withIcon) {
      drawThermometer(ctx, x + PAD, HEADER_TOP + Math.trunc((LABEL_BAND - iconSize) / 2), iconSize, iconColor);
    }
    ctx.font = fontAt(size);
    ctx.fillStyle = labelColor;
    ctx.fillText(
      label.toUpperCase(),
      x + PAD + iconSize + iconGap,
      HEADER_TOP + baselineFor(Math.trunc(LABEL_BAND), cap.height, cap.top),
    );
  }

  /**
   * FieldRenderer.render's number: fitted to a fixed "00.0" budget so a field keeps its text size
   * as digits come and go, its decimal optionally small and raised, then placed in the space below
   * the header row the way the layout's fit* ImageView would place the bitmap.
   */
  function drawValue(ctx, x, viewW, viewH, text, color, raised) {
    const [primary, secondary] = splitTail(text, raised);
    const [tPrimary, tSecondary] = splitTemplate(TEMPLATE, raised);
    const boxWidth = viewW - 2 * PAD;
    const fullBox = viewH - VALUE_BOTTOM - HEADER_HEIGHT;

    let size = TEXT_SIZE;
    let digits = inkBounds(DIGITS, size);
    let templateWidth = advance(tPrimary, size) + advance(tSecondary, size / 2);
    if (digits.height <= 0 || templateWidth <= 0) return;
    if (boxWidth > 0 && fullBox > 0) {
      const fit = Math.min(boxWidth / templateWidth, fullBox / digits.height);
      size = clamp(TEXT_SIZE * fit, 12, 400);
      digits = inkBounds(DIGITS, size);
      templateWidth = advance(tPrimary, size) + advance(tSecondary, size / 2);
    }

    let primarySize = size;
    let digitTop = digits.top;
    let digitHeight = digits.height;
    let primaryWidth = advance(primary, primarySize);
    let secondaryWidth = secondary ? advance(secondary, primarySize / 2) : 0;
    let gap = secondary ? primarySize * 0.08 : 0;

    // A value wider than the room it has -- a Fahrenheit core over 100 -- shrinks to fit.
    const natural = primaryWidth + gap + secondaryWidth;
    const room = Math.max(templateWidth, boxWidth);
    if (natural > room) {
      primarySize = size * room / natural;
      const shrunk = inkBounds(DIGITS, primarySize);
      digitTop = shrunk.top;
      digitHeight = shrunk.height;
      primaryWidth = advance(primary, primarySize);
      secondaryWidth = secondary ? advance(secondary, primarySize / 2) : 0;
      gap = secondary ? primarySize * 0.08 : 0;
    }

    const valueWidth = primaryWidth + gap + secondaryWidth;
    const w = Math.max(Math.ceil(templateWidth), Math.ceil(valueWidth));
    const h = digits.height;
    const left = (w - valueWidth) / 2;
    const baseline = baselineFor(h, digitHeight, digitTop);

    const availW = viewW - 2 * PAD;
    const availH = viewH - HEADER_HEIGHT - VALUE_BOTTOM;
    const s = Math.min(availW / w, availH / h);
    ctx.save();
    ctx.translate(x + PAD + (availW - w * s) / 2, HEADER_HEIGHT + (availH - h * s) / 2);
    ctx.scale(s, s);
    ctx.fillStyle = color;
    ctx.font = fontAt(primarySize);
    ctx.fillText(primary, left, baseline);
    if (secondary) {
      // Superscript: its ink top on the primary's, so it reads as part of the value.
      const small = inkBounds(DIGITS, primarySize / 2);
      ctx.font = fontAt(primarySize / 2);
      ctx.fillText(secondary, left + primaryWidth + gap, baseline + digitTop - small.top);
    }
    ctx.restore();
  }

  // ---------------------------------------------------------------------------------------------
  // HeatPill.

  // The value's digits are 66% of the pill's height; corrected three times, as ink comes back in
  // whole pixels and a single ratio lands close rather than on.
  const pillSizes = new Map();
  function pillTextSize(h) {
    if (!pillSizes.has(h)) {
      const target = h * 0.66;
      let size = target;
      for (let i = 0; i < 3; i++) {
        const ink = inkBounds('0', size).height;
        if (ink <= 0) break;
        size *= target / ink;
      }
      pillSizes.set(h, size);
    }
    return pillSizes.get(h);
  }

  function pillWidth(h, text, segments) {
    let w = 2 * PAD + advance(text, pillTextSize(h)) + h * 0.74 + h * 0.17;
    if (segments > 0) w += segments * h * 0.38 + (segments - 1) * h * 0.09 + h * 0.17;
    return Math.ceil(w);
  }

  /** HeatPill.render, on a row [rowHeight] tall with the pill's middle on [centreY]. */
  function drawPill(ctx, x, rowHeight, centreY, pill, solid, color, night) {
    const h = PILL_HEIGHT;
    const squares = solid ? 0 : pill.segments;
    const w = pillWidth(h, pill.text, squares);
    const y = clamp(Math.round(centreY - h / 2), 0, rowHeight - h);
    const plainInk = inkFor(night);
    const filled = solid && color != null;

    // An opaque copy of the card first: the track is translucent, and would show the divider and
    // the halves' zone fill through it.
    fillRoundRect(ctx, x, y, w, h, h / 2, cardFor(night));
    fillRoundRect(ctx, x, y, w, h, h / 2,
      filled ? color : night ? 'rgba(255,255,255,0.141)' : 'rgba(0,0,0,0.102)');

    const ink = filled ? onColor(color) : plainInk;
    let cx = PAD;
    const iconSize = Math.round(h * 0.74);
    drawThermometer(ctx, x + Math.round(cx), Math.round(y + (h - iconSize) / 2), iconSize, ink);
    cx += iconSize + h * 0.17;

    if (squares > 0) {
      const side = h * 0.38;
      const gap = h * 0.09;
      const top = y + (h - side) / 2;
      const empty = night ? 'rgba(255,255,255,0.2)' : 'rgba(0,0,0,0.141)';
      for (let i = 0; i < squares; i++) {
        // Every lit square in the CURRENT band's colour: the pill answers "which band am I in".
        fillRoundRect(ctx, x + cx + i * (side + gap), top, side, side, side * 0.25,
          i < pill.lit ? color ?? plainInk : empty);
      }
      cx += squares * side + (squares - 1) * gap + h * 0.17;
    }

    const size = pillTextSize(h);
    const zero = inkBounds('0', size);
    ctx.font = fontAt(size);
    ctx.fillStyle = ink;
    ctx.fillText(pill.text, x + cx, y + baselineFor(h, zero.height, zero.top));
  }

  /** HudMetric.pillFrame: squares lit, colour and text for one metric's value. */
  function pillFrame(metric, value, mode) {
    if (value == null) return { lit: 0, segments: 4, color: null, text: '--' };
    let lit;
    let color;
    let text;
    if (metric === 'zone') {
      lit = heatZone(value);
      color = ZONE_COLORS[lit - 1];
      text = format.tenths(value);
    } else if (metric === 'load') {
      lit = LOAD_STEPS.filter((step) => value > step).length;
      color = LOAD_COLORS[lit - 1] ?? null;
      text = format.tenths(value);
    } else {
      lit = adaptationLevel(value) + 1;
      color = LEVEL_COLORS[lit - 1];
      text = format.percent(value);
    }
    return { lit, segments: 4, color: mode === 'off' ? null : color, text };
  }

  /**
   * HudField's pillLayout: the labels and the pill with its squares if they fit; then a solid pill;
   * then the pill alone in the row, squares first; and on a tile too narrow for any pill, none.
   */
  function pillLayout(leftHalf, rightHalf, leftLabel, rightLabel, squaresPill, solidPill) {
    const besideLabels = Math.min(DIVIDER + rightHalf - leftLabel, DIVIDER + leftHalf - rightLabel);
    const alone = leftHalf + DIVIDER + rightHalf - 2 * PAD;
    if (squaresPill <= besideLabels) return { labels: true, style: 'squares' };
    if (solidPill <= besideLabels) return { labels: true, style: 'solid' };
    if (squaresPill <= alone) return { labels: false, style: 'squares' };
    if (solidPill <= alone) return { labels: false, style: 'solid' };
    return { labels: true, style: null };
  }

  // ---------------------------------------------------------------------------------------------
  // The fields.

  /** BaseNumericField.compute's colouring: the number in the band's colour, or filled with it. */
  function visual(text, band, mode, ink) {
    const zone = text === '--' || mode === 'off' ? null : band;
    if (!zone) return { text, color: ink, background: null };
    return mode === 'fill'
      ? { text, color: onColor(zone), background: zone }
      : { text, color: zone, background: null };
  }

  const FIELDS = {
    core: {
      label: 'CORE', name: 'Heat - Core Temp',
      text: (v, st) => format.temperature(v.core, st.fahrenheit), band: (v) => zoneColor(v.hsi),
    },
    skin: {
      label: 'SKIN', name: 'Heat - Skin Temp',
      text: (v, st) => format.temperature(v.skin, st.fahrenheit), band: (v) => zoneColor(v.hsi),
    },
    hsi: {
      label: 'HSI', name: 'Heat - Strain Index',
      text: (v) => format.tenths(v.hsi), band: (v) => zoneColor(v.hsi),
    },
    zone: {
      label: 'HEAT Z', name: 'Heat - Zone',
      text: (v) => format.count(heatZone(v.hsi)), band: (v) => zoneColor(v.hsi),
    },
    load: {
      // CORE gives the load no colours, so its own field has none.
      label: 'HEAT LOAD', name: 'Heat - Training Load',
      text: (v) => format.tenths(v.load), band: () => null,
    },
    adaptation: {
      label: 'HEAT ADAPT', name: 'Heat - Adaptation',
      text: (v) => format.percent(v.adaptation), band: (v) => LEVEL_COLORS[adaptationLevel(v.adaptation)],
    },
  };

  const HUD_NAMES = { zone: 'Heat - HUD Zone', load: 'Heat - HUD Training Load', adaptation: 'Heat - HUD Adaptation' };
  const metricValue = (metric, v) => (metric === 'zone' ? v.hsi : metric === 'load' ? v.load : v.adaptation);
  const unit = (st) => (st.fahrenheit ? '°F' : '°C');

  /** Karoo's own card, rounded to the corner it draws its cards with, and clipped to it. */
  function card(ctx, w, h, night) {
    roundRect(ctx, 0, 0, w, h, CARD_RADIUS);
    ctx.clip();
    ctx.fillStyle = cardFor(night);
    ctx.fillRect(0, 0, w, h);
  }

  function drawField(ctx, w, h, key, values, st) {
    const field = FIELDS[key];
    const ink = inkFor(st.night);
    const v = visual(field.text(values, st), field.band(values), st.mode, ink);
    card(ctx, w, h, st.night);
    if (v.background) {
      ctx.fillStyle = v.background;
      ctx.fillRect(0, 0, w, h);
    }
    const onFill = v.background ? onColor(v.background) : null;
    drawHeader(ctx, Math.trunc((w - headerWidth(field.label, true)) / 2), field.label,
      onFill ?? ink, onFill ?? ICON_GREEN, true);
    drawValue(ctx, 0, w, h, v.text, v.color, st.raised);
    const suffix = key === 'core' || key === 'skin' ? ` ${unit(st)}` : key === 'adaptation' ? ' %' : '';
    return `${field.name}: ${v.text}${suffix}`;
  }

  /**
   * HudField.hudViews: core and skin side by side, each the CORE or SKIN field drawn into its own
   * half without its header, a hairline between them, and one row across the top for the labels
   * and the pill.
   */
  function drawHud(ctx, w, h, metric, values, st) {
    const ink = inkFor(st.night);
    const usable = w - DIVIDER;
    const leftW = Math.trunc(usable / 2);
    const rightW = usable - leftW;
    const band = zoneColor(values.hsi);
    const left = visual(format.temperature(values.core, st.fahrenheit), band, st.mode, ink);
    const right = visual(format.temperature(values.skin, st.fahrenheit), band, st.mode, ink);

    card(ctx, w, h, st.night);
    if (left.background) {
      ctx.fillStyle = left.background;
      ctx.fillRect(0, 0, leftW, h);
    }
    if (right.background) {
      ctx.fillStyle = right.background;
      ctx.fillRect(leftW + DIVIDER, 0, rightW, h);
    }
    ctx.fillStyle = '#808080';
    ctx.fillRect(leftW, 0, DIVIDER, h);
    drawValue(ctx, 0, leftW, h, left.text, left.color, st.raised);
    drawValue(ctx, leftW + DIVIDER, rightW, h, right.text, right.color, st.raised);

    const labelInk = left.background ? onColor(left.background) : ink;
    const coreLabel = headerWidth('CORE', false);
    const skinLabel = headerWidth('SKIN', false);
    const pill = pillFrame(metric, metricValue(metric, values), st.mode);
    const budget = (segments) => Math.max(
      pillWidth(PILL_HEIGHT, pill.text, segments), pillWidth(PILL_HEIGHT, TEMPLATE, segments));
    const layout = pillLayout(leftW, rightW, coreLabel, skinLabel, budget(pill.segments), budget(0));

    if (layout.labels) {
      drawHeader(ctx, Math.round((leftW - coreLabel) / 2), 'CORE', labelInk, labelInk, false);
      drawHeader(ctx, leftW + DIVIDER + Math.round((rightW - skinLabel) / 2), 'SKIN', labelInk, labelInk, false);
    }
    if (layout.style) {
      const solid = layout.style === 'solid';
      // A solid pill in the very colour the halves are filled with would vanish into them.
      const color = solid && pill.color === left.background ? null : pill.color;
      const pw = pillWidth(PILL_HEIGHT, pill.text, solid ? 0 : pill.segments);
      drawPill(ctx, Math.trunc((w - pw) / 2), HEADER_HEIGHT, LABEL_CENTRE, pill, solid, color, st.night);
    }
    const what = metric === 'zone' ? `heat zone ${heatZone(values.hsi)}, index ${pill.text}`
      : metric === 'load' ? `training load ${pill.text}` : `adaptation ${pill.text} %`;
    return `${HUD_NAMES[metric]}: core ${left.text}, skin ${right.text} ${unit(st)}, ${what}`;
  }

  /** One pill on its own, on a strip of card, for the load steps and adaptation levels. */
  function drawPillTile(ctx, w, h, metric, value, st) {
    card(ctx, w, h, st.night);
    const pill = pillFrame(metric, value, st.mode);
    const pw = pillWidth(PILL_HEIGHT, pill.text, pill.segments);
    drawPill(ctx, Math.trunc((w - pw) / 2), h, h / 2, pill, false, pill.color, st.night);
    return `${metric === 'load' ? 'Training load' : 'Adaptation'} pill: ${pill.text}, ${pill.lit} of 4 squares lit`;
  }

  // A data page as a Karoo 2 lays one out: a 1px inset round every tile, 2px between them.
  const SCREEN_TILES = [
    { hud: 'zone', x: 1, y: 1, w: 478, h: 264 },
    { field: 'hsi', x: 1, y: 267, w: 238, h: 265 },
    { field: 'zone', x: 241, y: 267, w: 238, h: 265 },
    { field: 'load', x: 1, y: 534, w: 238, h: 265 },
    { field: 'adaptation', x: 241, y: 534, w: 238, h: 265 },
  ];

  function drawScreen(ctx, w, h, values, st) {
    ctx.fillStyle = st.night ? '#2a2a2a' : '#c9c9c9';
    ctx.fillRect(0, 0, w, h);
    const labels = SCREEN_TILES.map((tile) => {
      ctx.save();
      ctx.translate(tile.x, tile.y);
      const label = tile.hud
        ? drawHud(ctx, tile.w, tile.h, tile.hud, values, st)
        : drawField(ctx, tile.w, tile.h, tile.field, values, st);
      ctx.restore();
      return label;
    });
    return `A Karoo data page of CORE Heat fields. ${labels.join('. ')}.`;
  }

  // ---------------------------------------------------------------------------------------------
  // Painting the page's canvases.

  // FieldCatalog's previews tell one story: a 38.6 °C core on 35.1 °C skin is a Heat Strain Index
  // of 4.1, in heat zone 3 -- the one CORE trains in.
  const STORY = { core: 38.6, skin: 35.1, hsi: 4.1, load: 6.1, adaptation: 72.1 };
  const hero = { core: STORY.core };
  const explorer = { core: 38.6, skin: 35.1 };
  const settings = { mode: 'text', raised: true, night: true, fahrenheit: false };

  function valuesFor(canvas) {
    const { source } = canvas.dataset;
    if (source === 'hero') return { ...STORY, core: hero.core, hsi: heatStrainIndex(hero.core, STORY.skin) };
    if (source === 'explorer') return { ...explorer, hsi: heatStrainIndex(explorer.core, explorer.skin) };
    return STORY;
  }

  // The width and pixel ratio each canvas was last painted at, so a resize repaints only the ones
  // it changed: opening the table view changes the page's height, and none of its tiles.
  const paintedAt = new WeakMap();
  const sizeKey = (cssWidth) => `${cssWidth}@${window.devicePixelRatio || 1}`;

  /**
   * Draws one tile at [cssWidth], the width it is shown at. Painting several, measure them all
   * first and pass each its width: a canvas's new size invalidates the page's layout, and reading
   * the next one's width straight after would force the whole page to be laid out again, once
   * per tile.
   */
  function paint(canvas, cssWidth = canvas.clientWidth) {
    const w = Number(canvas.dataset.w);
    const h = Number(canvas.dataset.h);
    if (!cssWidth) return;
    paintedAt.set(canvas, sizeKey(cssWidth));
    const dpr = window.devicePixelRatio || 1;
    const pxWidth = Math.round(cssWidth * dpr);
    const pxHeight = Math.round(cssWidth * dpr * h / w);
    if (canvas.width !== pxWidth) canvas.width = pxWidth;
    if (canvas.height !== pxHeight) canvas.height = pxHeight;
    const ctx = canvas.getContext('2d');
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, pxWidth, pxHeight);
    ctx.setTransform(pxWidth / w, 0, 0, pxHeight / h, 0, 0);
    ctx.save();
    const values = valuesFor(canvas);
    const { tile } = canvas.dataset;
    let label = '';
    if (tile === 'screen') label = drawScreen(ctx, w, h, values, settings);
    else if (tile === 'hud') label = drawHud(ctx, w, h, canvas.dataset.metric, values, settings);
    else if (tile === 'pill') label = drawPillTile(ctx, w, h, canvas.dataset.metric, Number(canvas.dataset.value), settings);
    else label = drawField(ctx, w, h, canvas.dataset.field, values, settings);
    ctx.restore();
    if (canvas.getAttribute('aria-label') !== label) canvas.setAttribute('aria-label', label);
    // The outline the page draws round a tile, rounded to the card's own corner at this size.
    const radius = `${(CARD_RADIUS * cssWidth / w).toFixed(2)}px`;
    if (tile !== 'screen' && canvas.style.borderRadius !== radius) canvas.style.borderRadius = radius;
  }

  const canvases = [...document.querySelectorAll('canvas[data-tile]')];
  const paintAll = () => {
    const widths = canvases.map((canvas) => canvas.clientWidth);
    canvases.forEach((canvas, i) => paint(canvas, widths[i]));
  };

  let pending = false;
  function schedulePaint() {
    if (pending) return;
    pending = true;
    requestAnimationFrame(() => {
      pending = false;
      paintAll();
      refreshChart();
    });
  }

  /** On a resize, only the canvases shown at a new width or pixel ratio need drawing again. */
  let resizePending = false;
  function scheduleResize() {
    if (resizePending) return;
    resizePending = true;
    requestAnimationFrame(() => {
      resizePending = false;
      const widths = canvases.map((canvas) => canvas.clientWidth);
      canvases.forEach((canvas, i) => {
        if (paintedAt.get(canvas) !== sizeKey(widths[i])) paint(canvas, widths[i]);
      });
      refreshChart();
    });
  }

  /**
   * The page's one moment of motion: a ride warming up on the hero's Karoo, the core climbing a
   * tenth at a time, so the fields can be seen to follow the heat zones -- green, then yellow,
   * then the orange of the zone CORE trains in. Only on a first view of the hero, and never for
   * anyone who has asked for less motion.
   */
  const WARM_UP_FROM = 37.0;
  function shouldWarmUp(screen) {
    return screen != null
      && !window.matchMedia('(prefers-reduced-motion: reduce)').matches
      && screen.getBoundingClientRect().top < window.innerHeight;
  }

  function warmUp(screen) {
    let core = WARM_UP_FROM;
    const timer = setInterval(() => {
      core = tenth(core + 0.1);
      hero.core = core;
      paint(screen);
      if (core >= STORY.core) clearInterval(timer);
    }, 140);
  }

  // ---------------------------------------------------------------------------------------------
  // The preview settings: CORE Heat's own two, and the Karoo's theme and units.

  function initSettings() {
    document.querySelectorAll('[data-setting]').forEach((input) => {
      input.addEventListener('change', () => {
        const key = input.dataset.setting;
        if (input.type === 'checkbox') settings[key] = input.checked;
        else if (key === 'night' || key === 'fahrenheit') settings[key] = input.value === 'true';
        else settings[key] = input.value;
        if (key === 'fahrenheit') updateExplorer();
        schedulePaint();
      });
    });
  }

  // ---------------------------------------------------------------------------------------------
  // The zone chart: CORE's four zones over the core/skin plane, by the estimate above.

  const SVG_NS = 'http://www.w3.org/2000/svg';
  // Sized to the width the chart is shown at, one unit to a CSS pixel, so its text stays the size
  // it is set at instead of shrinking with the chart on a phone.
  let CHART = { w: 640, h: 400, left: 54, right: 16, top: 14, bottom: 50 };
  const SKIN_RANGE = [28, 38];
  const CORE_RANGE = [36.5, 40.5];
  const chartX = (skin) => CHART.left + (skin - SKIN_RANGE[0]) / (SKIN_RANGE[1] - SKIN_RANGE[0]) * (CHART.w - CHART.left - CHART.right);
  const chartY = (core) => CHART.h - CHART.bottom - (core - CORE_RANGE[0]) / (CORE_RANGE[1] - CORE_RANGE[0]) * (CHART.h - CHART.top - CHART.bottom);
  const skinAt = (x) => SKIN_RANGE[0] + (x - CHART.left) / (CHART.w - CHART.left - CHART.right) * (SKIN_RANGE[1] - SKIN_RANGE[0]);
  const coreAtY = (y) => CORE_RANGE[0] + (CHART.h - CHART.bottom - y) / (CHART.h - CHART.top - CHART.bottom) * (CORE_RANGE[1] - CORE_RANGE[0]);

  const toF = (c) => c * 9 / 5 + 32;
  const toC = (f) => (f - 32) * 5 / 9;
  const showTemp = (c) => `${fixed1(settings.fahrenheit ? toF(c) : c)} ${unit(settings)}`;

  function el(name, attrs, parent) {
    const node = document.createElementNS(SVG_NS, name);
    Object.entries(attrs).forEach(([k, v]) => node.setAttribute(k, v));
    if (parent) parent.appendChild(node);
    return node;
  }

  const pathOf = (points) => points.map(([x, y], i) => `${i ? 'L' : 'M'}${x.toFixed(2)},${y.toFixed(2)}`).join('');

  const chartWidthFor = (svg) => Math.round(clamp(svg.parentElement.clientWidth || 640, 280, 1200));

  function drawZoneChart(svg) {
    const w = chartWidthFor(svg);
    CHART = { ...CHART, w, h: Math.round(clamp(w * 0.62, 300, 420)) };
    svg.setAttribute('viewBox', `0 0 ${CHART.w} ${CHART.h}`);
    const desc = svg.querySelector('desc');
    svg.replaceChildren(...(desc ? [desc] : []));
    const skins = [];
    for (let i = 0; i <= 200; i++) skins.push(SKIN_RANGE[0] + i * (SKIN_RANGE[1] - SKIN_RANGE[0]) / 200);
    // A zone changes where the PRINTED index does: at 0.95, 2.95 and 6.95.
    const edge = (hsi) => skins.map((s) => [chartX(s), chartY(clamp(coreAt(hsi, s), ...CORE_RANGE))]);
    const flat = (core) => skins.map((s) => [chartX(s), chartY(core)]);
    const edges = [flat(CORE_RANGE[0]), edge(0.95), edge(2.95), edge(6.95), flat(CORE_RANGE[1])];

    const plot = el('g', { class: 'plot' }, svg);
    for (let z = 0; z < 4; z++) {
      const area = pathOf(edges[z]) + pathOf([...edges[z + 1]].reverse()).replace('M', 'L') + 'Z';
      el('path', { d: area, class: 'zone-area', fill: ZONE_COLORS[z] }, plot);
    }
    for (let z = 1; z < 4; z++) el('path', { d: pathOf(edges[z]), class: 'zone-edge' }, plot);

    // Axes, in the unit the toolbar picked: ticks on round numbers of THAT unit.
    const axes = el('g', { class: 'axes' }, svg);
    const f = settings.fahrenheit;
    const xTicks = f ? [84, 88, 92, 96, 100].map(toC) : [28, 30, 32, 34, 36, 38];
    const yTicks = f ? [98, 100, 102, 104].map(toC) : [37, 38, 39, 40];
    const tickText = (c) => String(Math.round(f ? toF(c) : c));
    xTicks.forEach((c) => {
      const x = chartX(c);
      el('line', { x1: x, x2: x, y1: CHART.top, y2: CHART.h - CHART.bottom, class: 'grid' }, axes);
      el('text', { x, y: CHART.h - CHART.bottom + 20, class: 'tick', 'text-anchor': 'middle' }, axes).textContent = tickText(c);
    });
    yTicks.forEach((c) => {
      const y = chartY(c);
      el('line', { x1: CHART.left, x2: CHART.w - CHART.right, y1: y, y2: y, class: 'grid' }, axes);
      el('text', { x: CHART.left - 10, y: y + 5, class: 'tick', 'text-anchor': 'end' }, axes).textContent = tickText(c);
    });
    el('line', { x1: CHART.left, x2: CHART.w - CHART.right, y1: CHART.h - CHART.bottom, y2: CHART.h - CHART.bottom, class: 'axis' }, axes);
    el('line', { x1: CHART.left, x2: CHART.left, y1: CHART.top, y2: CHART.h - CHART.bottom, class: 'axis' }, axes);
    el('text', { x: (CHART.left + CHART.w - CHART.right) / 2, y: CHART.h - 8, class: 'axis-title', 'text-anchor': 'middle' }, axes)
      .textContent = `Skin temperature (${unit(settings)})`;
    el('text', {
      x: -(CHART.top + CHART.h - CHART.bottom) / 2, y: 16, class: 'axis-title', 'text-anchor': 'middle', transform: 'rotate(-90)',
    }, axes).textContent = `Core temperature (${unit(settings)})`;

    // Each zone named in place, so none depends on its colour to be told apart -- and away from
    // where the point starts, at the story's 35.1 °C skin.
    const labels = el('g', { class: 'zone-labels' }, svg);
    [[29.6, 0], [31.2, 1], [37.0, 2], [35.6, 3]].forEach(([skin, z]) => {
      const lower = z === 0 ? CORE_RANGE[0] : coreAt([0.95, 2.95, 6.95][z - 1], skin);
      const upper = z === 3 ? CORE_RANGE[1] : coreAt([0.95, 2.95, 6.95][z], skin);
      el('text', { x: chartX(skin), y: chartY((lower + upper) / 2) + 6, class: 'zone-label', 'text-anchor': 'middle' }, labels)
        .textContent = `Zone ${z + 1}`;
    });

    const ghost = el('g', { class: 'ghost', visibility: 'hidden' }, svg);
    el('circle', { r: 5 }, ghost);
    const marker = el('g', { class: 'marker' }, svg);
    el('line', { class: 'cross cross-x' }, marker);
    el('line', { class: 'cross cross-y' }, marker);
    el('circle', { r: 7, class: 'dot' }, marker);
    return { marker, ghost };
  }

  let chartParts = null;

  function placeMarker() {
    if (!chartParts) return;
    const hsi = heatStrainIndex(explorer.core, explorer.skin);
    const x = chartX(explorer.skin);
    const y = chartY(explorer.core);
    const [crossX, crossY, dot] = chartParts.marker.children;
    Object.entries({ x1: CHART.left, x2: x, y1: y, y2: y }).forEach(([k, v]) => crossX.setAttribute(k, v));
    Object.entries({ x1: x, x2: x, y1: y, y2: CHART.h - CHART.bottom }).forEach(([k, v]) => crossY.setAttribute(k, v));
    dot.setAttribute('cx', x);
    dot.setAttribute('cy', y);
    dot.setAttribute('fill', zoneColor(hsi));
  }

  function updateExplorer() {
    const hsi = heatStrainIndex(explorer.core, explorer.skin);
    const zone = heatZone(hsi);
    const core = document.getElementById('core-input');
    const skin = document.getElementById('skin-input');
    if (!core || !skin) return;
    core.value = explorer.core;
    skin.value = explorer.skin;
    core.setAttribute('aria-valuetext', showTemp(explorer.core));
    skin.setAttribute('aria-valuetext', showTemp(explorer.skin));
    document.getElementById('core-output').textContent = showTemp(explorer.core);
    document.getElementById('skin-output').textContent = showTemp(explorer.skin);
    document.getElementById('hsi-output').textContent = fixed1(hsi);
    document.getElementById('zone-output').textContent = `Zone ${zone}`;
    document.getElementById('zone-name').textContent =
      zone === 3 ? `${ZONE_NAMES[2]}, the zone to train in` : ZONE_NAMES[zone - 1];
    document.getElementById('zone-swatch').style.background = ZONE_COLORS[zone - 1];
    refreshChart();
    placeMarker();
    canvases.filter((c) => c.dataset.source === 'explorer').forEach((c) => paint(c));
  }

  /** Redraws the chart when its unit or the width it is shown at has changed. */
  function refreshChart() {
    const svg = document.getElementById('zone-chart');
    if (!svg) return;
    if (chartParts && svg.dataset.unit === unit(settings) && chartWidthFor(svg) === CHART.w) return;
    chartParts = drawZoneChart(svg);
    svg.dataset.unit = unit(settings);
    placeMarker();
  }

  function initExplorer() {
    const svg = document.getElementById('zone-chart');
    if (!svg) return;
    const tip = document.getElementById('chart-tip');
    const coreInput = document.getElementById('core-input');
    const skinInput = document.getElementById('skin-input');
    coreInput.addEventListener('input', () => { explorer.core = Number(coreInput.value); updateExplorer(); });
    skinInput.addEventListener('input', () => { explorer.skin = Number(skinInput.value); updateExplorer(); });

    const pointAt = (event) => {
      const pt = svg.createSVGPoint();
      pt.x = event.clientX;
      pt.y = event.clientY;
      const p = pt.matrixTransform(svg.getScreenCTM().inverse());
      return {
        skin: tenth(clamp(skinAt(p.x), ...SKIN_RANGE)),
        core: tenth(clamp(coreAtY(p.y), ...CORE_RANGE)),
      };
    };

    const showTip = (event, at) => {
      const hsi = heatStrainIndex(at.core, at.skin);
      const zone = heatZone(hsi);
      tip.replaceChildren();
      const head = document.createElement('strong');
      head.textContent = `HSI ${fixed1(hsi)}, zone ${zone}`;
      const key = document.createElement('i');
      key.style.background = ZONE_COLORS[zone - 1];
      head.prepend(key);
      const detail = document.createElement('span');
      detail.textContent = `Core ${showTemp(at.core)}, skin ${showTemp(at.skin)}`;
      tip.append(head, detail);
      const box = svg.parentElement.getBoundingClientRect();
      const left = event.clientX - box.left;
      tip.hidden = false;
      tip.style.left = `${clamp(left, tip.offsetWidth / 2 + 4, box.width - tip.offsetWidth / 2 - 4)}px`;
      tip.style.top = `${event.clientY - box.top}px`;
      chartParts.ghost.setAttribute('visibility', 'visible');
      chartParts.ghost.setAttribute('transform', `translate(${chartX(at.skin)},${chartY(at.core)})`);
    };

    const hideTip = () => {
      tip.hidden = true;
      chartParts?.ghost.setAttribute('visibility', 'hidden');
    };

    let dragging = false;
    svg.addEventListener('pointerdown', (event) => {
      dragging = true;
      svg.setPointerCapture(event.pointerId);
      Object.assign(explorer, pointAt(event));
      updateExplorer();
      hideTip();
    });
    svg.addEventListener('pointermove', (event) => {
      const at = pointAt(event);
      if (dragging) {
        Object.assign(explorer, at);
        updateExplorer();
      } else if (event.pointerType === 'mouse') {
        showTip(event, at);
      }
    });
    const stop = () => { dragging = false; };
    svg.addEventListener('pointerup', stop);
    svg.addEventListener('pointercancel', stop);
    svg.addEventListener('pointerleave', hideTip);
    updateExplorer();
  }

  // ---------------------------------------------------------------------------------------------
  // The latest release, for the download button. The button works without it.

  async function showLatestRelease() {
    const target = document.querySelector('[data-release]');
    if (!target) return;
    try {
      let release = null;
      try { release = JSON.parse(sessionStorage.getItem('coreheat-release')); } catch { /* none kept */ }
      if (!release) {
        const response = await fetch('https://api.github.com/repos/angkyria/karoo-core/releases/latest', {
          headers: { Accept: 'application/vnd.github+json' },
        });
        if (!response.ok) return;
        const json = await response.json();
        release = { tag: String(json.tag_name), date: String(json.published_at) };
        try { sessionStorage.setItem('coreheat-release', JSON.stringify(release)); } catch { /* fine */ }
      }
      const date = new Date(release.date);
      if (!/^v\d+\.\d+\.\d+$/.test(release.tag) || Number.isNaN(date.getTime())) return;
      const day = date.toLocaleDateString(undefined, { day: 'numeric', month: 'long', year: 'numeric' });
      target.textContent = `Version ${release.tag.slice(1)}, released ${day}.`;
    } catch {
      // Offline, or the API's rate limit: the button still downloads the latest APK.
    }
  }

  // ---------------------------------------------------------------------------------------------

  async function start() {
    try {
      await document.fonts.load(`40px ${FAMILY}`, DIGITS);
    } catch {
      // Without the face every field falls back to the browser's sans; still drawn, just not DIN.
    }
    labelSize = null;
    pillSizes.clear();
    measured.clear();
    initSettings();
    const screen = canvases.find((c) => c.dataset.source === 'hero');
    const warm = shouldWarmUp(screen);
    if (warm) hero.core = WARM_UP_FROM;
    paintAll();
    if (warm) setTimeout(() => warmUp(screen), 500);
    initExplorer();
    // Its first call, straight after observe(), finds every canvas at the size it was just painted.
    new ResizeObserver(scheduleResize).observe(document.body);
    window.addEventListener('resize', scheduleResize);
    showLatestRelease();
  }

  start();
})();
