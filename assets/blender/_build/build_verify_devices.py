# VeriBorder device Blender builder (verification/terminal devices)
# Reconstructs the block model geometry straight from
#   src/main/resources/assets/veriborder/models/block/<id>.json
# renders a front preview PNG, saves a .blend and exports an OBJ.
#
# Coordinate contract (matches the Minecraft block model):
#   MC (x, y, z)  ->  Blender (x/16, z/16, y/16)   (1 unit = 16 px)
#   front / north face = MC z-min = Blender -Y side.
#
# Usage:
#   blender --background --python build_verify_devices.py -- <device_id>

import bpy
import json
import math
import os
import sys

from mathutils import Vector, Matrix

ROOT = "C:/Users/GhostBubbles/Documents/deepseek-harness/default-workspace/VeriBorder"
MODEL_DIR = os.path.join(ROOT, "src/main/resources/assets/veriborder/models/block")
BLENDER_DIR = os.path.join(ROOT, "assets/blender")

# per-device palettes (mirrors tools/generate_assets.ps1)
PALETTES = {
    "hotel_reader":       {"base": "#F0F5F4", "base2": "#D8E9E6", "trim": "#3F5A57", "accent": "#2FC6B7", "fg": "#9BFFF2", "bg": "#062421"},
    "desk_scanner":       {"base": "#E8EDF0", "base2": "#D2DADF", "trim": "#3E4C55", "accent": "#1FA89C", "fg": "#8FF0E4", "bg": "#061F22"},
    "manual_terminal":    {"base": "#E9EDF0", "base2": "#D3DBE1", "trim": "#414D57", "accent": "#2FC6B7", "fg": "#9BE9FF", "bg": "#071C22"},
    "airport_checkin":    {"base": "#E6EFF8", "base2": "#CFE1F2", "trim": "#3B5366", "accent": "#3E8FC4", "fg": "#9FD4FF", "bg": "#081A2A"},
    "station_verifier":   {"base": "#EAF0F1", "base2": "#D4E2E4", "trim": "#3F5255", "accent": "#1FA89C", "fg": "#9BFFF2", "bg": "#061E20"},
    "loss_terminal":      {"base": "#F5E9E9", "base2": "#EAD2D2", "trim": "#5C3F3F", "accent": "#D9534F", "fg": "#FFB4AE", "bg": "#200808"},
    "renewal_machine":    {"base": "#F6EFE3", "base2": "#E8DAC4", "trim": "#5A4E3E", "accent": "#C98A3E", "fg": "#FFD9A0", "bg": "#1B1408"},
    "blacklist_terminal": {"base": "#E4E7EA", "base2": "#CCD2D8", "trim": "#3A4149", "accent": "#C9393F", "fg": "#FF9E9E", "bg": "#1A0608"},
}


def fail(msg):
    print("ERROR: " + msg)
    sys.exit(1)


def hex_rgb(h):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16))


def mix(a, b, t):
    return tuple(int(a[i] * (1 - t) + b[i] * t) for i in range(3))


def brighten(c, t):
    return mix(c, (255, 255, 255), t)


def darken(c, t):
    return mix(c, (0, 0, 0), t)


# ---------------------------------------------------------------- arguments
argv = sys.argv
argv = argv[argv.index("--") + 1:] if "--" in argv else []
if len(argv) != 1:
    fail("pass exactly one device id after --")

dev_id = argv[0]
if dev_id not in PALETTES:
    fail("unknown device id: %s" % dev_id)

pal = PALETTES[dev_id]
model_json = os.path.join(MODEL_DIR, dev_id + ".json")
out_dir = os.path.join(BLENDER_DIR, dev_id)
os.makedirs(out_dir, exist_ok=True)

with open(model_json, "r", encoding="utf-8") as f:
    MODEL = json.load(f)

elements = MODEL.get("elements", [])
if len(elements) < 10:
    fail("%s: only %d elements (need >= 10)" % (dev_id, len(elements)))

base = hex_rgb(pal["base"])
base2 = hex_rgb(pal["base2"])
trim = hex_rgb(pal["trim"])
accent = hex_rgb(pal["accent"])
fg = hex_rgb(pal["fg"])
bg = hex_rgb(pal["bg"])

# texture-name -> (base, emission_or_None, emission_strength, metallic, rough, transmission)
COLOR_MAP = {
    "body":    (base, None, 0.0, 0.0, 0.55, 0.0),
    "body2":   (base2, None, 0.0, 0.0, 0.55, 0.0),
    "trim":    (trim, None, 0.0, 0.0, 0.45, 0.0),
    "top":     (brighten(base, 0.10), None, 0.0, 0.0, 0.50, 0.0),
    "accent":  (accent, None, 0.0, 0.0, 0.40, 0.0),
    "detail":  (darken(trim, 0.45), None, 0.0, 0.0, 0.50, 0.0),
    "led":     (darken(accent, 0.35), brighten(accent, 0.45), 5.0, 0.0, 0.30, 0.0),
    "glass":   ((196, 220, 230), None, 0.0, 0.0, 0.08, 0.55),
    "paper":   ((246, 244, 236), None, 0.0, 0.0, 0.75, 0.0),
    "warning": (accent, None, 0.0, 0.0, 0.45, 0.0),
    "screen":  (bg, fg, 1.5, 0.0, 0.25, 0.0),
    "screen2": (bg, fg, 1.0, 0.0, 0.28, 0.0),
}

# ---------------------------------------------------------------- clean slate
for obj in list(bpy.context.scene.objects):
    bpy.data.objects.remove(obj, do_unlink=True)


# ---------------------------------------------------------------- materials
materials = {}


def get_material(name):
    if name in materials:
        return materials[name]
    spec = COLOR_MAP.get(name, COLOR_MAP["body"])
    base_c, emi_c, emi_s, metal, rough, trans = spec
    mat = bpy.data.materials.new(name)
    if not mat.use_nodes:
        mat.use_nodes = True
    mat.diffuse_color = (base_c[0] / 255.0, base_c[1] / 255.0, base_c[2] / 255.0, 1.0)
    bsdf = mat.node_tree.nodes.get("Principled BSDF")
    bsdf.inputs["Base Color"].default_value = (base_c[0] / 255.0, base_c[1] / 255.0, base_c[2] / 255.0, 1.0)
    bsdf.inputs["Metallic"].default_value = metal
    bsdf.inputs["Roughness"].default_value = rough
    if trans > 0.0:
        bsdf.inputs["Transmission Weight"].default_value = trans
        bsdf.inputs["IOR"].default_value = 1.45
    if emi_c is not None:
        bsdf.inputs["Emission Color"].default_value = (emi_c[0] / 255.0, emi_c[1] / 255.0, emi_c[2] / 255.0, 1.0)
        bsdf.inputs["Emission Strength"].default_value = emi_s
    materials[name] = mat
    return mat


# ---------------------------------------------------------------- geometry
def mc_to_bl(v):
    return Vector((v[0] / 16.0, v[2] / 16.0, v[1] / 16.0))


def element_face_texture(el):
    faces = el.get("faces", {})
    for key in ("north", "south", "up", "down", "east", "west"):
        if key in faces:
            t = faces[key].get("texture", "#body")
            return t.lstrip("#")
    return "body"


AXIS_MAP = {"x": "X", "y": "Z", "z": "Y"}


def euler_for(baxis, rad):
    if baxis == "X":
        return (rad, 0.0, 0.0)
    if baxis == "Y":
        return (0.0, rad, 0.0)
    return (0.0, 0.0, rad)


built = 0
for i, el in enumerate(elements):
    frm = el["from"]
    to = el["to"]
    p1 = mc_to_bl(frm)
    p2 = mc_to_bl(to)
    center = (p1 + p2) * 0.5
    size = Vector((abs(p2.x - p1.x), abs(p2.y - p1.y), abs(p2.z - p1.z)))
    if size.x <= 1e-6 or size.y <= 1e-6 or size.z <= 1e-6:
        continue

    bpy.ops.mesh.primitive_cube_add(size=1.0, location=(0, 0, 0))
    obj = bpy.context.active_object
    obj.name = "%s_e%02d" % (dev_id, i)
    obj.scale = (size.x, size.y, size.z)

    tex = element_face_texture(el)
    mat = get_material(tex)
    if len(obj.data.materials) == 0:
        obj.data.materials.append(mat)
    else:
        obj.data.materials[0] = mat

    rot = el.get("rotation")
    if rot:
        axis = rot["axis"]
        angle = rot["angle"]
        origin = mc_to_bl(rot["origin"])
        # MC rotation -> Blender rotation: axes remap (x->X, y->Z, z->Y) and the
        # angle is negated because the MC->Blender coordinate map is a reflection.
        baxis = AXIS_MAP.get(axis, "X")
        rad = math.radians(-angle)
        obj.rotation_euler = euler_for(baxis, rad)
        R = Matrix.Rotation(rad, 4, baxis)
        obj.location = origin + R @ (center - origin)
    else:
        obj.location = center

    built += 1


# ---------------------------------------------------------------- bbox / camera
def world_bbox():
    """Bounding box computed directly from the MC element data (rotations
    applied with the same right-hand rule Minecraft uses), mapped to Blender."""
    mn = Vector((1e9, 1e9, 1e9))
    mx = Vector((-1e9, -1e9, -1e9))
    for el in elements:
        x1, y1, z1 = el["from"]
        x2, y2, z2 = el["to"]
        rot = el.get("rotation")
        ox = oy = oz = 0.0
        ang = 0.0
        axis = "x"
        if rot:
            ox, oy, oz = rot["origin"]
            ang = math.radians(rot["angle"])
            axis = rot["axis"]
        c = math.cos(ang)
        s = math.sin(ang)
        for x in (x1, x2):
            for y in (y1, y2):
                for z in (z1, z2):
                    dx, dy, dz = x - ox, y - oy, z - oz
                    if axis == "x":
                        rx, ry, rz = dx, dy * c - dz * s, dy * s + dz * c
                    elif axis == "y":
                        rx, ry, rz = dx * c + dz * s, dy, -dx * s + dz * c
                    else:
                        rx, ry, rz = dx * c - dy * s, dx * s + dy * c, dz
                    wx, wy, wz = ox + rx, oy + ry, oz + rz
                    bv = Vector((wx / 16.0, wz / 16.0, wy / 16.0))
                    mn.x = min(mn.x, bv.x); mn.y = min(mn.y, bv.y); mn.z = min(mn.z, bv.z)
                    mx.x = max(mx.x, bv.x); mx.y = max(mx.y, bv.y); mx.z = max(mx.z, bv.z)
    return mn, mx


def aim_at(obj, location, target):
    """Point the object's local -Z toward `target` from `location`."""
    direction = (Vector(target) - Vector(location)).normalized()
    obj.location = Vector(location)
    obj.rotation_mode = "QUATERNION"
    obj.rotation_quaternion = Vector((0, 0, -1)).rotation_difference(direction)


mn, mx = world_bbox()
center = (mn + mx) * 0.5
span_x = mx.x - mn.x
span_z = mx.z - mn.z
ortho = max(span_x, span_z) * 1.30

cam_data = bpy.data.cameras.new("Camera")
cam_data.type = "ORTHO"
cam_data.ortho_scale = ortho
cam_obj = bpy.data.objects.new("Camera", cam_data)
bpy.context.scene.collection.objects.link(cam_obj)
aim_at(cam_obj, (center.x, mn.y - 3.0, center.z), center)
bpy.context.scene.camera = cam_obj


# ---------------------------------------------------------------- lights
def add_area(loc, size, energy):
    bpy.ops.object.light_add(type="AREA", location=(0, 0, 0))
    light = bpy.context.active_object
    light.data.energy = energy
    light.data.size = size
    aim_at(light, loc, center)
    return light


add_area((center.x + 1.7, center.y - 3.1, center.z + 2.1), 2.2, 30)
add_area((center.x - 2.1, center.y - 2.4, center.z + 0.9), 1.6, 12)
add_area((center.x, center.y + 2.1, center.z + 1.7), 1.8, 20)


# ---------------------------------------------------------------- world
world = bpy.data.worlds.get("World")
if world is None:
    world = bpy.data.worlds.new("World")
bpy.context.scene.world = world
if not world.use_nodes:
    world.use_nodes = True
wbg = world.node_tree.nodes.get("Background")
wbg.inputs[0].default_value = (0.82, 0.84, 0.86, 1.0)
wbg.inputs[1].default_value = 0.6


# (no floor plane: a clean uniform backdrop keeps the front preview readable)


# ---------------------------------------------------------------- render
scene = bpy.context.scene
scene.render.engine = "CYCLES"
scene.cycles.samples = 48
scene.cycles.use_denoising = True
scene.cycles.denoiser = "OPENIMAGEDENOISE"
scene.render.resolution_x = 1024
scene.render.resolution_y = 1024
scene.render.film_transparent = False
scene.render.image_settings.file_format = "PNG"
scene.render.image_settings.color_mode = "RGB"
scene.view_settings.view_transform = "Standard"
scene.view_settings.look = "None"

preview_path = os.path.join(out_dir, dev_id + "_front.png")
scene.render.filepath = preview_path
bpy.ops.render.render(write_still=True)


# ---------------------------------------------------------------- save blend
blend_path = os.path.join(out_dir, dev_id + ".blend")
bpy.ops.wm.save_as_mainfile(filepath=blend_path)


# ---------------------------------------------------------------- export obj
obj_path = os.path.join(out_dir, dev_id + ".obj")
bpy.ops.object.select_all(action="DESELECT")
for o in bpy.context.scene.objects:
    if o.type == "MESH" and o.name.startswith(dev_id + "_"):
        o.select_set(True)
bpy.ops.wm.obj_export(filepath=obj_path, export_selected_objects=True)

print("BUILT %s: elements=%d built=%d" % (dev_id, len(elements), built))
print("BLEND " + blend_path)
print("OBJ   " + obj_path)
print("PNG   " + preview_path)
