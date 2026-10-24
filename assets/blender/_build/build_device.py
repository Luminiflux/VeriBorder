# VeriBorder device Blender builder (stamp / print devices)
# Reads a per-device JSON config (path in $VB_CONFIG), reconstructs the block
# model geometry from the actual models/block/<id>.json, renders a front preview
# PNG, saves a .blend and exports an OBJ.
#
# Coordinate contract (matches the Minecraft block model):
#   MC (x, y, z)  ->  Blender (x/16, z/16, y/16)   (1 unit = 16 px)
#   front/north face = MC z-min = Blender -Y side.

import bpy
import json
import math
import os
import sys

from mathutils import Vector, Matrix


def fail(msg):
    print("ERROR: " + msg)
    sys.exit(1)


# ---------------------------------------------------------------- config
cfg_path = os.environ.get("VB_CONFIG")
if not cfg_path:
    fail("VB_CONFIG env var not set")

with open(cfg_path, "r", encoding="utf-8-sig") as f:
    CFG = json.load(f)

dev_id = CFG["id"]
model_json = CFG["model_json"]
out_dir = CFG["out_dir"]
pal = CFG["palette"]

os.makedirs(out_dir, exist_ok=True)

with open(model_json, "r", encoding="utf-8") as f:
    MODEL = json.load(f)

elements = MODEL.get("elements", [])
if len(elements) < 10:
    fail("%s: only %d elements (need >= 10)" % (dev_id, len(elements)))


# ---------------------------------------------------------------- palette
def hex_rgb(h):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16))


def mix(a, b, t):
    return tuple(int(a[i] * (1 - t) + b[i] * t) for i in range(3))


def brighten(c, t):
    return mix(c, (255, 255, 255), t)


def darken(c, t):
    return mix(c, (0, 0, 0), t)


base = hex_rgb(pal["base"])
base2 = hex_rgb(pal["base2"])
trim = hex_rgb(pal["trim"])
accent = hex_rgb(pal["accent"])
fg = hex_rgb(pal["fg"])
bg = hex_rgb(pal["bg"])

# texture-name -> (base_color, emission_color_or_None, emission_strength,
#                  metallic, roughness, transmission)
COLOR_MAP = {
    "body":    (base, None, 0.0, 0.0, 0.55, 0.0),
    "body2":   (base2, None, 0.0, 0.0, 0.55, 0.0),
    "trim":    (trim, None, 0.0, 0.35, 0.45, 0.0),
    "top":     (brighten(base, 0.10), None, 0.0, 0.0, 0.50, 0.0),
    "accent":  (accent, None, 0.0, 0.15, 0.40, 0.0),
    "detail":  (darken(trim, 0.45), None, 0.0, 0.25, 0.50, 0.0),
    "led":     (darken(accent, 0.35), brighten(accent, 0.45), 5.0, 0.0, 0.30, 0.0),
    "glass":   ((196, 220, 230), None, 0.0, 0.0, 0.08, 0.55),
    "paper":   ((246, 244, 236), None, 0.0, 0.0, 0.75, 0.0),
    "warning": ((36, 36, 40), None, 0.0, 0.2, 0.55, 0.0),
    "screen":  (fg, fg, 0.7, 0.0, 0.22, 0.0),
    "screen2": (fg, fg, 0.45, 0.0, 0.25, 0.0),
}


# ---------------------------------------------------------------- materials
materials = {}


def get_material(name):
    if name in materials:
        return materials[name]
    spec = COLOR_MAP.get(name, COLOR_MAP["body"])
    base_c, emi_c, emi_s, metal, rough, trans = spec
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    nodes = mat.node_tree.nodes
    nodes.clear()
    bsdf = nodes.new("ShaderNodeBsdfPrincipled")
    out = nodes.new("ShaderNodeOutputMaterial")
    mat.node_tree.links.new(bsdf.outputs["BSDF"], out.inputs["Surface"])
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


# ---------------------------------------------------------------- clean slate
# The default startup scene ships a Cube/Camera/Light; remove them so nothing
# overlaps or competes with the device geometry we build below.
for o in list(bpy.data.objects):
    bpy.data.objects.remove(o, do_unlink=True)
for m in list(bpy.data.materials):
    bpy.data.materials.remove(m)


# ---------------------------------------------------------------- geometry
def mc_to_bl(v):
    return Vector((v[0] / 16.0, v[2] / 16.0, v[1] / 16.0))


def rotate_vec(v, axis, angle_deg):
    rad = math.radians(angle_deg)
    if axis == "x":
        return Matrix.Rotation(rad, 4, "X") @ v
    if axis == "y":
        return Matrix.Rotation(rad, 4, "Y") @ v
    if axis == "z":
        return Matrix.Rotation(rad, 4, "Z") @ v
    return v


def element_face_texture(el):
    faces = el.get("faces", {})
    for key in ("north", "south", "up", "down", "east", "west"):
        if key in faces:
            t = faces[key].get("texture", "#body")
            return t.lstrip("#")
    return "body"


def euler_for(axis, angle_deg):
    rad = math.radians(angle_deg)
    if axis == "x":
        return (rad, 0.0, 0.0)
    if axis == "y":
        return (0.0, rad, 0.0)
    if axis == "z":
        return (0.0, 0.0, rad)
    return (0.0, 0.0, 0.0)


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

    rot = el.get("rotation")
    tex = element_face_texture(el)
    mat = get_material(tex)
    if len(obj.data.materials) == 0:
        obj.data.materials.append(mat)
    else:
        obj.data.materials[0] = mat

    if rot:
        axis = rot["axis"]
        angle = rot["angle"]
        origin = mc_to_bl(rot["origin"])
        obj.rotation_euler = euler_for(axis, angle)
        R = Matrix.Rotation(math.radians(angle), 4, axis.upper())
        obj.location = origin + R @ (center - origin)
    else:
        obj.location = center


# ---------------------------------------------------------------- camera
cam_data = bpy.data.cameras.new("Camera")
cam_data.type = "PERSP"
cam_data.lens = 55.0
cam_obj = bpy.data.objects.new("Camera", cam_data)
bpy.context.scene.collection.objects.link(cam_obj)
cam_obj.location = (0.5, -2.8, 0.70)
cam_dir = (Vector((0.5, 0.5, 0.55)) - cam_obj.location).normalized()
cam_obj.rotation_euler = cam_dir.to_track_quat("-Z", "Z").to_euler()
bpy.context.scene.camera = cam_obj


# ---------------------------------------------------------------- lights
def add_point(loc, energy, radius=0.06):
    bpy.ops.object.light_add(type="POINT", location=loc)
    light = bpy.context.active_object
    light.data.energy = energy
    light.data.shadow_soft_size = radius
    return light


add_point((2.2, -2.6, 2.6), 420)   # key, upper front-left
add_point((-1.7, -1.9, 1.4), 90)    # fill, front-right
add_point((0.5, 2.6, 2.3), 220)     # rim, back-top
add_point((0.5, -0.9, 0.4), 60)     # low front fill


# ---------------------------------------------------------------- world
world = bpy.data.worlds.get("World")
if world is None:
    world = bpy.data.worlds.new("World")
bpy.context.scene.world = world
world.use_nodes = True
wbg = world.node_tree.nodes.get("Background")
wbg.inputs[0].default_value = (0.40, 0.42, 0.46, 1.0)
wbg.inputs[1].default_value = 1.0


# shadow-catcher floor just under the block
bpy.ops.mesh.primitive_plane_add(size=6.0, location=(0.5, 0.5, -0.006))
floor = bpy.context.active_object
floor.name = "Floor"
floor.is_shadow_catcher = True


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

print("BUILT %s: elements=%d objects=%d" % (dev_id, len(elements), len(elements)))
print("BLEND " + blend_path)
print("OBJ   " + obj_path)
print("PNG   " + preview_path)
