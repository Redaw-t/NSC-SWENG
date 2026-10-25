# Nickel Sales Center Frontend Style Guide

**Status:** Active Iteration 2 Frontend Standard  
**Owner:** PM / BA  
**Applies To:** All Nickel Sales Center desktop modules  

> If a screen-specific mockup conflicts with this document, the latest approved PM/BA mockup takes precedence for layout. This document remains the source of truth for colors, typography, spacing, and general component styling.

---

## 1. Visual Concept

The Nickel Sales Center desktop application should feel like **modern local business management software**.

The interface should be:

- clean
- professional
- light
- easy to scan
- practical for daily POS and inventory use
- visually consistent across modules
- simple enough for staff to learn quickly

The frontend should prioritize usability over decoration.

### Avoid

- excessive gradients
- neon or highly saturated decorative colors
- heavy shadows
- overly rounded pill-style controls
- inconsistent fonts
- separate visual themes for different modules
- excessive animation
- decorative elements that reduce readability
- overly dense layouts without spacing hierarchy

---

## 2. Universal Color Palette

| Role | Color | Hex |
|---|---|---|
| Primary | Royal Blue | `#2457A6` |
| Dark Primary | Navy Blue | `#173B70` |
| Accent | Coral Red | `#E45757` |
| Background | Cool Light Gray | `#F5F7FA` |
| Surface / Card | White | `#FFFFFF` |
| Primary Text | Charcoal | `#1F2937` |
| Secondary Text | Gray | `#6B7280` |
| Border / Divider | Light Gray | `#D9DEE7` |
| Disabled | Soft Gray | `#ECEFF3` |
| Success | Green | `#2E8B57` |
| Warning | Amber | `#D99A24` |
| Error | Red | `#D64545` |
| Information | Blue | `#3B82C4` |

### Color Usage Rules

**Blue** is the main application color.

Use blue for normal primary actions such as:

- Save
- Add
- Continue
- Search
- Confirm
- Update
- Apply

Use coral/red sparingly.

Red or coral should mainly represent:

- Delete
- Deactivate
- Cancel Transaction
- critical warnings
- error states
- destructive actions

Use green for successful states and confirmations.

Use amber for warnings, low-stock conditions, or caution.

Do not assign random module-specific colors.

---

## 3. Typography

### Preferred Font

**Inter**

Use one font family throughout the application.

If Inter cannot be bundled or loaded reliably in Java, use:

**Segoe UI**

Do not mix Inter and Segoe UI within the same application build.

### Recommended Weights

- Regular
- Medium
- SemiBold
- Bold

### Typography Scale

| Usage | Size | Weight |
|---|---:|---|
| Main Page Title | 26–30 px | Bold |
| Section Title | 20–22 px | SemiBold |
| Card Heading | 17–18 px | SemiBold |
| Body | 14–16 px | Regular |
| Form Label | 13–14 px | Medium |
| Button | 14–15 px | SemiBold |
| Table Header | 13–14 px | SemiBold |
| Table Content | 13–14 px | Regular |
| Helper / Caption | 12–13 px | Regular |

Avoid very small text for desktop UI.

---

## 4. Application Shell

All authenticated modules should use the same application shell.

```text
┌─────────────────────────────────────────────────────────────┐
│ Logo    Nickel Sales Center               User ▾   Logout   │
├──────────────┬──────────────────────────────────────────────┤
│              │                                              │
│ Dashboard    │ PAGE TITLE                                   │
│ POS          │ Supporting description / breadcrumb          │
│ Products     │                                              │
│ Inventory    │                                              │
│ Reports      │             PAGE CONTENT                     │
│ Users        │                                              │
│ Settings     │                                              │
│              │                                              │
└──────────────┴──────────────────────────────────────────────┘
```

### Rules

- Use one consistent shell across modules.
- Permissions control which navigation items are visible.
- Do not create a different navigation system for each role.
- Role differences should affect available modules, not the entire visual identity.
- The active module should be visually obvious.

---

## 5. Spacing System

Use consistent spacing instead of arbitrary gaps.

| Usage | Spacing |
|---|---:|
| Very small internal gap | 4 px |
| Small gap | 8 px |
| Related controls | 12 px |
| Standard spacing | 16 px |
| Card / section padding | 20–24 px |
| Major section separation | 24–32 px |
| Page-level separation | 32–40 px |

Prefer multiples of 4.

---

## 6. Corner Radius

| Component | Radius |
|---|---:|
| Buttons | 8–10 px |
| Inputs | 8 px |
| Cards | 12 px |
| Dialogs | 12–16 px |
| Status Chips | Pill / rounded as appropriate |

Avoid turning normal controls into oversized pills.

---

## 7. Buttons

### Primary Button

- Primary Blue background
- White text
- SemiBold label
- approximately 44–46 px height
- 8–10 px radius

Examples: Save, Add Product, Confirm, Continue, Apply.

### Secondary Button

- White background
- Blue border
- Blue text

Examples: Back, Close, View Details, Cancel a non-destructive dialog.

### Danger Button

- Error or Accent Red background
- White text

Examples: Delete, Deactivate, Cancel Transaction.

Do not use danger styling for ordinary actions.

---

## 8. Input Fields

Standard inputs should use:

- 44–46 px height
- 8 px radius
- light gray border
- white background
- dark text
- blue border or focus state

Use labels above fields.

```text
Username
[____________________________]
```

Avoid relying entirely on placeholders.

Helper text or validation errors should appear directly below the related field when appropriate.

---

## 9. Tables

Use:

- white background
- light gray or subtle blue table header
- thin row separators
- clear column spacing
- 13–14 px text
- left-aligned names and descriptions
- right-aligned prices, quantities, totals, and other numeric values
- consistent row heights

| Product | Stock | Price | Status |
|---|---:|---:|---|
| Bond Paper | 24 | ₱180.00 | In Stock |
| Notebook | 4 | ₱35.00 | Low Stock |
| Marker | 0 | ₱25.00 | Out of Stock |

---

## 10. Status Chips

| Color | Meaning |
|---|---|
| Green | In Stock / Active / Success |
| Amber | Low Stock / Warning |
| Red | Out of Stock / Error / Failed |
| Gray | Inactive / Disabled |
| Blue | Informational / Processing |

Do not communicate status using color alone. Include readable text.

---

## 11. Cards and Panels

Use cards for dashboard metrics, summary information, grouped forms, and important content sections.

Recommended style:

- white background
- 12 px radius
- thin border
- subtle shadow only when useful
- 20–24 px internal padding

Avoid unnecessary nested cards.

---

## 12. Navigation

Navigation should:

- remain visually consistent across modules
- clearly indicate the active module
- respect user permissions
- use simple icons only where they improve recognition
- avoid excessive decorative icons

### Active State

- blue tint or blue accent
- SemiBold text

### Inactive State

- dark or secondary text
- neutral background

---

## 13. Login Screen

The login screen should be visually simple and neutral.

```text
            [ NSC LOGO ]

        NICKEL SALES CENTER
      POS & Inventory Management

      ┌──────────────────────┐
      │ Welcome back         │
      │ Sign in to continue  │
      │                      │
      │ Username             │
      │ [________________]   │
      │                      │
      │ Password          👁 │
      │ [________________]   │
      │                      │
      │ [     SIGN IN     ]  │
      └──────────────────────┘
```

Guidelines:

- centered content
- cool light gray background
- white login card
- primary blue Sign In button
- subtle branding
- avoid making login appear POS-only
- show validation near the related input where possible

### Mockup

![Login Mockup](../ui-mockups/login.png)

> Placeholder: add the approved login mockup at `docs/ui-mockups/login.png`.

---

## 14. Dashboard

The dashboard should summarize relevant information without becoming visually overloaded.

Possible components:

- sales summary
- inventory warnings
- quick actions
- recent activity
- module shortcuts

Use role permissions to control what information appears.

### Mockup

![Dashboard Mockup](../ui-mockups/dashboard.png)

> Placeholder: add the approved dashboard mockup at `docs/ui-mockups/dashboard.png`.

---

## 15. POS Screen

POS should prioritize speed, readability, and keyboard use.

```text
┌────────────────────────────────────────────────────────────┐
│ Product Search / Barcode                                   │
├────────────────────────────────────┬───────────────────────┤
│ Cart                               │ Order Summary         │
│                                    │                       │
│ Product   Qty   Price   Total      │ Subtotal              │
│ ...                                │ VAT                   │
│                                    │ Total                 │
│                                    │                       │
│                                    │ [ PAY ]               │
└────────────────────────────────────┴───────────────────────┘
```

Requirements:

- keyboard-friendly
- barcode/search input should have a strong focus state
- clearly visible total
- stock warnings must be noticeable
- payment CTA should be visually dominant
- avoid decorative clutter
- preserve fast cashier workflow

### Mockup

![POS Mockup](../ui-mockups/pos.png)

> Placeholder: add the approved POS mockup at `docs/ui-mockups/pos.png`.

---

## 16. Product Management

```text
Products

[ Search... ] [ Category ▼ ]                   [ + Add Product ]

┌────────────────────────────────────────────────────────────┐
│ Product │ Category │ Supplier │ Retail │ Wholesale │ Stock │
├────────────────────────────────────────────────────────────┤
│ ...                                                        │
└────────────────────────────────────────────────────────────┘
```

Add/Edit Product may use a modal dialog or dedicated form page.

### Mockup

![Product Management Mockup](../ui-mockups/product-management.png)

> Placeholder: add the approved Product Management mockup at `docs/ui-mockups/product-management.png`.

---

## 17. Inventory Management

Recommended areas:

- current stock
- stock-in
- stock-out
- manual adjustment
- reorder monitoring
- low-stock monitoring
- stock movement history

```text
Inventory

[ Search ] [ Stock Status ▼ ]

[ Total Products ] [ Low Stock ] [ Out of Stock ]

┌────────────────────────────────────────────────────────────┐
│ Product │ Current Stock │ Reorder Level │ Status │ Actions │
└────────────────────────────────────────────────────────────┘
```

Warnings should use status colors rather than a separate visual theme.

### Mockup

![Inventory Management Mockup](../ui-mockups/inventory-management.png)

> Placeholder: add the approved Inventory Management mockup at `docs/ui-mockups/inventory-management.png`.

---

## 18. Barcode Management

Barcode screens should use the same form, table, button, and navigation rules.

Typical actions may include assigning, generating, validating, and printing barcodes.

### Mockup

![Barcode Management Mockup](../ui-mockups/barcode-management.png)

> Placeholder: add the approved Barcode Management mockup at `docs/ui-mockups/barcode-management.png`.

---

## 19. Reports

Reports should emphasize readability and data hierarchy.

Use clear filters, consistent date controls, readable tables, strong totals and summaries, and restrained color use.

### Mockup

![Reports Mockup](../ui-mockups/reports.png)

> Placeholder: add the approved Reports mockup at `docs/ui-mockups/reports.png`.

---

## 20. User Management

User Management should follow the same table, form, dialog, and status styles.

Typical information may include username, role, status, and account actions.

### Mockup

![User Management Mockup](../ui-mockups/user-management.png)

> Placeholder: add the approved User Management mockup at `docs/ui-mockups/user-management.png`.

---

## 21. System / Settings

System and Settings screens should remain visually simple.

Use grouped forms and clear labels. Do not use a separate theme merely because the module is administrative.

### Mockup

![System Settings Mockup](../ui-mockups/system-settings.png)

> Placeholder: add the approved System / Settings mockup at `docs/ui-mockups/system-settings.png`.

---

## 22. Dialogs and Confirmation Windows

Dialogs should clearly identify the action, keep content concise, use consistent button ordering, and visually distinguish dangerous actions.

```text
Deactivate Product?

This product will no longer be available for new sales.

[ Cancel ] [ Deactivate ]
```

Use red only for the destructive confirmation button.

---

## 23. Icons

- use one icon library or icon style where possible
- do not randomly mix filled, outlined, and cartoon-style icons
- icons should support text, not replace all labels
- critical actions should still include readable text labels

---

## 24. Accessibility and Usability

- maintain strong text/background contrast
- do not communicate status using color alone
- ensure focus states are visible
- avoid very small click targets
- support keyboard navigation where practical
- keep important actions easy to identify
- use clear wording rather than ambiguous icon-only actions

---

## 25. Before Building a New Screen

- [ ] Check this style guide first.
- [ ] Check whether an approved mockup already exists.
- [ ] Reuse existing colors, typography, and components.
- [ ] Do not invent a new module-specific theme.
- [ ] Use the shared application shell.
- [ ] Use blue for normal primary actions.
- [ ] Reserve red for destructive or critical actions.
- [ ] Follow the spacing and font hierarchy.
- [ ] Reuse existing UI components where practical.
- [ ] Coordinate before changing shared UI components.
- [ ] Document deviations when technical limitations prevent matching an approved mockup.

---

## 26. Mockup Reference Paths

Approved UI references should be placed under:

`docs/ui-mockups/`

Recommended filenames:

```text
login.png
dashboard.png
pos.png
product-management.png
inventory-management.png
barcode-management.png
reports.png
user-management.png
system-settings.png
```

See `docs/ui-mockups/README.md` for mockup management rules.

---

## 27. Source of Truth

Use the following hierarchy when making frontend decisions:

1. Latest approved PM/BA screen mockup — screen-specific layout and workflow
2. `FRONTEND_STYLE_GUIDE.md` — colors, typography, spacing, component styling
3. Existing shared UI components — implementation consistency
4. Developer judgment — only where the above do not define the behavior

When unsure, coordinate with the PM/BA before introducing a new visual pattern.
