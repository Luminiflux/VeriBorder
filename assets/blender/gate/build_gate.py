# VeriBorder E-Gate Blender builder (26.2 design baseline).
#
# Models the split dual-pillar speed gate from the reference: matte-white body,
# black curved top cover + rear overhang, front vertical card-reader screen,
# cyan light strips, green arrow, and two framed glass door leaves (left_door /
# right_door, each 6 x 10 x 1 MC px) as independent axis-aligned cuboid objects.
#
# Coordinate contract (matches the Minecraft block model / task spec):
#   Blender X = MC x, Blender Y = MC z (depth), Blender Z = MC y (height)
#   1 Blender unit = 16 MC px   ->  mc_to_bl(v) = (v.x/16, v.z/16, v.y/16)
#
# Usage:
#   blender --background --python assets/blender/gate/build_gate.py
#
# Outputs (all in assets/blender/gate/):
#   gate.blend, gate.obj, gate.glb, preview.png

import bpy
import math
import os
import sys

from mathutils import Vector


def fail(msg):
    print("ERROR: " + msg)
    sys.exit(1)


HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.environ.get("VB_OUT", HERE)
os.makedirs(OUT, exist_ok=True)


def mc_to_bl(v):
    return Vector((v[0] / 16.0, v[2] / 16.0, v[1] / 16.0))


# ---------------------------------------------------------------- materials
def srgb(c):
    return (c[0] / 255.0, c[1] / 255.0, c[2] / 255.0, 1.0)


# name -> (base_color, emission_color_or_None, emission_strength,
#          metallic, roughness, transmission)
PALETTE = {
    "white_body":   ((236, 236, 234), None, 0.0, 0.0, 0.55, 0.0),
    "white_body2":  ((224, 226, 226), None, 0.0, 0.0, 0.55, 0.0),
    "dark_trim":    ((42, 45, 51), None, 0.0, 0.35, 0.45, 0.0),
    "black_top":    ((24, 26, 30), None, 0.0, 0.15, 0.38, 0.0),
    "frame_metal":  ((46, 50, 56), None, 0.0, 0.55, 0.35, 0.0),
    "glass":        ((188, 224, 236), None, 0.0, 0.0, 0.06, 0.62),
    "cyan_led":     ((70, 200, 235), (0, 229, 255), 6.0, 0.0, 0.25, 0.0),
    "green_arrow":  ((40, 200, 90), (47, 212, 90), 5.0, 0.0, 0.25, 0.0),
    "screen_bezel": ((30, 32, 38), None, 0.0, 0.2, 0.4, 0.0),
    "screen_face":  ((150, 205, 232), (150, 205, 232), 0.55, 0.0, 0.2, 0.0),
    "accent_rail":  ((150, 158, 168), None, 0.0, 0.25, 0.4, 0.0),
    "ground":       ((110, 116, 122), None, 0.0, 0.0, 0.9, 0.0),
}

materials = {}


def get_material(name):
    if name in materials:
        return materials[name]
    spec = PALETTE[name]
    base_c, emi_c, emi_s, metal, rough, trans = spec
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    bsdf = mat.node_tree.nodes.get("Principled BSDF")
    bsdf.inputs["Base Color"].default_value = srgb(base_c)
    bsdf.inputs["Metallic"].default_value = metal
    bsdf.inputs["Roughness"].default_value = rough
    if trans > 0.0:
        bsdf.inputs["Transmission Weight"].default_value = trans
        bsdf.inputs["IOR"].default_value = 1.45
        bsdf.inputs["Alpha"].default_value = 0.45
        mat.blend_method = "BLEND"
    if emi_c is not None:
        bsdf.inputs["Emission Color"].default_value = srgb(emi_c)
        bsdf.inputs["Emission Strength"].default_value = emi_s
    materials[name] = mat
    return mat


# ---------------------------------------------------------------- geometry
def box(name, mc_from, mc_to, material):
    p1 = mc_to_bl(mc_from)
    p2 = mc_to_bl(mc_to)
    center = (p1 + p2) * 0.5
    size = Vector((abs(p2.x - p1.x), abs(p2.y - p1.y), abs(p2.z - p1.z)))
    bpy.ops.mesh.primitive_cube_add(size=1.0, location=center)
    obj = bpy.context.active_object
    obj.name = name
    obj.scale = (size.x, size.y, size.z)
    obj.data.materials.append(get_material(material))
    return obj


# Clear the default startup objects.
for o in list(bpy.context.scene.objects):
    bpy.data.objects.remove(o, do_unlink=True)

# --- dual pillars (matte white body) ---
box("left_pillar", [-2.0, 0.0, 1.0], [2.0, 18.0, 15.0], "white_body")
box("right_pillar", [14.0, 0.0, 1.0], [18.0, 18.0, 15.0], "white_body")

# --- base plinths (slightly recessed dark trim) ---
box("left_base", [-2.0, 0.0, 1.0], [2.0, 1.0, 15.0], "dark_trim")
box("right_base", [14.0, 0.0, 1.0], [18.0, 1.0, 15.0], "dark_trim")

# --- black curved top cover + rear overhang ---
box("left_top_cover", [-2.2, 17.4, 0.8], [2.2, 20.2, 15.2], "black_top")
box("right_top_cover", [13.8, 17.4, 0.8], [18.2, 20.2, 15.2], "black_top")
box("left_top_overhang", [-2.1, 18.8, 14.9], [2.1, 20.2, 16.4], "black_top")
box("right_top_overhang", [13.9, 18.8, 14.9], [18.1, 20.2, 16.4], "black_top")

# --- front vertical card-reader screens (dark bezel + light face) ---
box("left_screen_bezel", [-1.55, 6.7, 0.83], [1.55, 12.95, 0.95], "screen_bezel")
box("left_screen_face", [-1.4, 7.0, 0.76], [1.4, 12.65, 0.84], "screen_face")
box("right_screen_bezel", [14.45, 6.7, 0.83], [17.55, 12.95, 0.95], "screen_bezel")
box("right_screen_face", [14.6, 7.0, 0.76], [17.4, 12.65, 0.84], "screen_face")

# --- cyan light strips (cover base seam + inner-side strip + floor chevrons) ---
box("left_cyan_seam", [-1.9, 17.9, 1.0], [1.9, 18.05, 15.0], "cyan_led")
box("right_cyan_seam", [14.1, 17.9, 1.0], [17.9, 18.05, 15.0], "cyan_led")
box("left_cyan_inner", [2.0, 3.4, 1.3], [2.14, 17.3, 1.42], "cyan_led")
box("right_cyan_inner", [13.86, 3.4, 1.3], [14.0, 17.3, 1.42], "cyan_led")

# --- green arrows on the top-cover front face ---
box("left_arrow", [-1.28, 18.9, 0.72], [1.28, 19.72, 0.8], "green_arrow")
box("right_arrow", [14.72, 18.9, 0.72], [17.28, 19.72, 0.8], "green_arrow")

# --- lane threshold between the pillars ---
box("lane_base", [2.0, 0.0, 6.85], [14.0, 0.2, 9.15], "accent_rail")

# --- framed glass door leaves (each 6 x 10 x 1 MC px, independent objects) ---
box("left_door_frame", [2.0, 3.0, 7.5], [8.0, 13.0, 8.5], "frame_metal")
box("left_door", [3.0, 4.0, 7.52], [7.0, 12.0, 8.48], "glass")
box("right_door_frame", [8.0, 3.0, 7.5], [14.0, 13.0, 8.5], "frame_metal")
box("right_door", [9.0, 4.0, 7.52], [13.0, 12.0, 8.48], "glass")


# ---------------------------------------------------------------- camera
cam_data = bpy.data.cameras.new("Camera")
cam_data.type = "PERSP"
cam_data.lens = 50.0
cam_obj = bpy.data.objects.new("Camera", cam_data)
bpy.context.scene.collection.objects.link(cam_obj)
cam_obj.location = (2.3, -2.6, 1.05)

look = Vector((0.5, 0.5, 0.58))
cam_obj.rotation_euler = (look - cam_obj.location).to_track_quat('-Z', 'Y').to_euler()

bpy.context.scene.camera = cam_obj


# ---------------------------------------------------------------- lights
def add_area(loc, size, energy, target):
    bpy.ops.object.light_add(type="AREA", location=loc)
    light = bpy.context.active_object
    light.data.energy = energy
    light.data.size = size
    light.rotation_euler = (Vector(target) - Vector(loc)).to_track_quat('-Z', 'Y').to_euler()
    return light


add_area((2.4, -2.4, 2.4), 2.4, 320, look)
add_area((-1.6, -1.8, 1.3), 1.6, 90, look)
add_area((0.5, 2.4, 2.0), 1.8, 140, look)


# ---------------------------------------------------------------- world
world = bpy.data.worlds.get("World")
if world is None:
    world = bpy.data.worlds.new("World")
bpy.context.scene.world = world
world.use_nodes = True
wbg = world.node_tree.nodes.get("Background")
wbg.inputs[0].default_value = (0.72, 0.76, 0.80, 1.0)
wbg.inputs[1].default_value = 0.7

# ground plane just under the block for contrast
bpy.ops.mesh.primitive_plane_add(size=6.0, location=(0.5, 0.5, -0.02))
floor = bpy.context.active_object
floor.name = "Floor"
floor.data.materials.append(get_material("ground"))


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

preview_path = os.path.join(OUT, "preview.png")
scene.render.filepath = preview_path
bpy.ops.render.render(write_still=True)


# ---------------------------------------------------------------- save blend
blend_path = os.path.join(OUT, "gate.blend")
bpy.ops.wm.save_as_mainfile(filepath=blend_path)


# ---------------------------------------------------------------- export OBJ
obj_path = os.path.join(OUT, "gate.obj")
bpy.ops.object.select_all(action="DESELECT")
for o in bpy.context.scene.objects:
    if o.type == "MESH":
        o.select_set(True)
bpy.ops.wm.obj_export(filepath=obj_path, export_selected_objects=True)


# ---------------------------------------------------------------- export GLB
glb_path = os.path.join(OUT, "gate.glb")
bpy.ops.export_scene.gltf(filepath=glb_path, export_format="GLB", use_selection=True)

mesh_count = sum(1 for o in bpy.context.scene.objects if o.type == "MESH")
print("BUILT gate: mesh_objects=%d" % mesh_count)
print("BLEND " + blend_path)
print("OBJ   " + obj_path)
print("GLB   " + glb_path)
print("PNG   " + preview_path)
