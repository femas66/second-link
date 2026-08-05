---
name: SecondLink Remote Bot Dashboard
description: A clean, professional monitoring interface for remote device control.
colors:
  primary: "#4a3c8c" # Deep Indigo Tooling (oklch(0.360 0.219 270.0))
  neutral-bg: "#ffffff" # Pure White (oklch(1.000 0.000 0))
  surface: "#f8f9fa" # Light Gray Surface (oklch(0.980 0.005 270.0))
  ink: "#1c1b1f" # Deep Charcoal (oklch(0.180 0.010 270.0))
  accent: "#2e7d32" # Success Green (oklch(0.500 0.150 145.0))
  error: "#d32f2f" # Error Red (oklch(0.500 0.200 25.0))
  muted: "#625b71" # Muted Secondary (oklch(0.450 0.050 270.0))
typography:
  display:
    fontFamily: "Inter, system-ui, sans-serif"
    fontSize: "clamp(2rem, 5vw, 3rem)"
    fontWeight: 700
    lineHeight: 1.2
    letterSpacing: "-0.02em"
  body:
    fontFamily: "Inter, system-ui, sans-serif"
    fontSize: "1rem"
    fontWeight: 400
    lineHeight: 1.5
    letterSpacing: "normal"
  label:
    fontFamily: "JetBrains Mono, monospace"
    fontSize: "0.875rem"
    fontWeight: 500
    letterSpacing: "0.05em"
rounded:
  sm: "4px"
  md: "12px"
  lg: "16px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "16px"
  lg: "24px"
  xl: "32px"
components:
  card:
    backgroundColor: "{colors.neutral-bg}"
    rounded: "{rounded.md}"
    padding: "24px"
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "#ffffff"
    rounded: "{rounded.sm}"
    padding: "12px 24px"
  status-pill:
    rounded: "100px"
    padding: "4px 12px"
    typography: "{typography.label}"
---

# Design System: SecondLink Remote Bot Dashboard

## 1. Overview

**Creative North Star: "The Mission Control Desk"**

The SecondLink Remote Bot Dashboard is designed to evoke the feeling of a professional monitoring station—precise, reliable, and uncluttered. It moves away from the consumer-grade "app" feel and leans into a functional, high-utility aesthetic that honors the user's need for remote control confidence.

The interface uses a **Restrained** color strategy. A pure white background provides maximum clarity, while a deep "indigo tooling" primary color anchors the brand. Success and error states are handled with clear, meaningful accents rather than decorative gradients.

**Key Characteristics:**
- **High Information Density**: Data is organized into clean, legible cards.
- **Status-First**: The bot's health is the most prominent visual element.
- **Monospaced Utility**: Technical data and logs use monospaced fonts for precision.

## 2. Colors

The palette is anchored by a deep indigo that feels like "software for craft." It is paired with high-contrast neutrals to ensure readability in any environment.

### Primary
- **Indigo Tooling** (#4a3c8c / oklch(0.360 0.219 270.0)): Used for branding, primary actions, and structural anchors.

### Neutral
- **Pure White** (#ffffff / oklch(1.000 0.000 0)): The canvas for the dashboard.
- **Deep Charcoal** (#1c1b1f / oklch(0.180 0.010 270.0)): Primary text color for maximum contrast.
- **Surface Gray** (#f8f9fa / oklch(0.980 0.005 270.0)): Used for backgrounds behind cards and subtle separators.

### Named Rules
**The Rarity Rule.** The primary indigo is used on less than 10% of the screen. Its presence signifies importance and control.

## 3. Typography

**Display & Body Font:** Inter (Modern Sans-Serif)
**Label/Mono Font:** JetBrains Mono (Technical Monospace)

### Hierarchy
- **Display** (700, clamp(2rem, 3rem), 1.2): Used for page titles and large status headers.
- **Headline** (600, 1.5rem, 1.4): Section headers.
- **Body** (400, 1rem, 1.5): The standard for all readable text and descriptions.
- **Label** (500, 0.875rem, 0.05em, uppercase): Used for status pills, technical logs, and field labels.

## 4. Elevation

The system is **Flat-By-Default**. Depth is conveyed through tonal layering (cards on a surface background) rather than heavy drop shadows.

### Shadow Vocabulary
- **Interactive Lift** (0 4px 12px rgba(0,0,0,0.08)): Only used on hover for interactive cards to signify clickability.

## 5. Components

### Cards
- **Shape:** Softly rounded corners (12px radius).
- **Style:** Pure white background with a subtle 1px border (#e0e0e0) instead of a shadow.
- **Padding:** Generous internal spacing (24px) for breathing room.

### Status Pills
- **Shape:** Full-pill (100px radius).
- **Typography:** JetBrains Mono, All-caps.
- **Success:** Light green background (oklch(0.95 0.05 145)) with deep green text.
- **Error:** Light red background (oklch(0.95 0.05 25)) with deep red text.

### Action Buttons
- **Primary:** Indigo background, white text, 4px radius.
- **Ghost:** Transparent background, indigo border, indigo text.

## 6. Do's and Don'ts

### Do:
- **Do** use `JetBrains Mono` for any technical output or time-stamped logs.
- **Do** maintain a strict 24px grid for card spacing.
- **Do** use pure white backgrounds for the primary content areas.

### Don't:
- **Don't** use purple gradients or glassmorphism effects.
- **Don't** use card shadows heavier than 8px blur at rest.
- **Don't** mix multiple sans-serif families. Stick to Inter.
