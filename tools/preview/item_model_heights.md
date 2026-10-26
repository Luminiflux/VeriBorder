VeriBorder dedicated 3D item models (tools/generate_item_models.js)
written: 19 file(s) under src\main\resources\assets\veriborder\models\item
two-block registry: 11 device(s) from src/main/java/com/veriborder/block/DeviceBlock.java (DeviceBlock.TALL) - passport_issuer, self_visa, self_endorsement, airport_checkin, station_verifier, blacklist_terminal, renewal_machine, entry_stamp, exit_stamp, stamp_history, manual_terminal

id                    kind      archetype           els   bbox (px)       seam      gui   shift  visual  verdict
--------------------------------------------------------------------------------------------------------------
id_issuer             1-block   issuerTower          26  13.0x11.8x13.2         -   0.625      0    7.34  PASS
passport_issuer       2-block   issuerTower+ext      31  12.0x26.1x13.2  16.00|+16  0.38388  -5.03   10.00  PASS
permit_issuer         1-block   issuerTower          26  14.0x11.0x13.2         -   0.625      0    6.88  PASS
visa_issuer           1-block   issuerTower          26  12.5x12.0x13.2         -   0.625      0    7.50  PASS
self_visa             2-block   issuerTower+ext      31  13.0x25.1x13.2  16.00|+16  0.3992  -4.53   10.00  PASS
endorsement_machine   1-block   issuerTower          26  13.0x12.5x13.2         -   0.625      0    7.81  PASS
self_endorsement      2-block   issuerTower+ext      31  12.5x26.9x13.2  16.00|+16  0.37244  -5.43   10.00  PASS
desk_scanner          1-block   readerWedge          22  13.0x7.8x13.0          -   0.625      0    4.91  PASS
station_verifier      2-block   readerWedge+ext      32  14.0x25.4x13.0  16.00|+16  0.3937   -4.7   10.00  PASS
hotel_reader          1-block   readerWedge          22  12.0x7.6x13.0          -   0.625      0    4.75  PASS
manual_terminal       2-block   readerWedge+ext      32  13.5x24.6x13.0  16.00|+16  0.4065   -4.3   10.00  PASS
airport_checkin       2-block   serviceKiosk+ext     28  14.0x26.4x13.0  16.00|+16  0.37879   -5.2   10.00  PASS
loss_terminal         1-block   serviceKiosk         20  13.0x9.4x13.0          -   0.625      0    5.88  PASS
renewal_machine       2-block   serviceKiosk+ext     28  14.0x25.8x13.0  16.00|+16  0.3876   -4.9   10.00  PASS
blacklist_terminal    2-block   serviceKiosk+ext     28  13.5x25.2x13.0  16.00|+16  0.39683   -4.6   10.00  PASS
print_preview         1-block   serviceKiosk         20  13.0x9.7x13.0          -   0.625      0    6.03  PASS
entry_stamp           2-block   stampPress+ext       29  13.6x23.7x11.8  16.00|+16  0.42194  -3.85   10.00  PASS
exit_stamp            2-block   stampPress+ext       29  13.1x24.2x11.8  16.00|+16  0.41322   -4.1   10.00  PASS
stamp_history         2-block   stampPress+ext       29  12.6x23.3x11.8  16.00|+16  0.42918  -3.65   10.00  PASS

bbox height of every TWO-BLOCK device item model (assertion: > 20 px):

id                    tier els  lower+ext  bbox height  gui scale  visual (blocks)
-------------------------------------------------------------------------------
passport_issuer         31       16.00|+16   26.05 px    0.38388            1.00
self_visa               31       16.00|+16   25.05 px     0.3992            1.00
self_endorsement        31       16.00|+16   26.85 px    0.37244            1.00
station_verifier        32       16.00|+16   25.40 px     0.3937            1.00
manual_terminal         32       16.00|+16   24.60 px     0.4065            1.00
airport_checkin         28       16.00|+16   26.40 px    0.37879            1.00
renewal_machine         28       16.00|+16   25.80 px     0.3876            1.00
blacklist_terminal      28       16.00|+16   25.20 px    0.39683            1.00
entry_stamp             29       16.00|+16   23.70 px    0.42194            1.00
exit_stamp              29       16.00|+16   24.20 px    0.41322            1.00
stamp_history           29       16.00|+16   23.30 px    0.42918            1.00

min elements: 20   max elements: 32

ASSERTIONS
  [PASS] two-block item models > 20 px tall (11/11 checked)
  [PASS] single-block item models <= 16.5 px tall
  [PASS] lower tier ends and extension tier starts at exactly y=16
  [PASS] gui visual height <= 1.0 block (10 px)
  [PASS] registry: DEVICES matches DeviceBlock.TALL
  [PASS] geometry: axis-aligned, unique per device, 20..40 elements, no coincident/enclosed faces

PASS: two-block items carry both halves, single-block items unchanged, no geometry problems
