import bpy
import json
import os
import sys
import mathutils

# ---- path resolution ----
BASE = r"C:\Users\GhostBubbles\Documents\deepseek-harness\default-workspace\VeriBorder"
DESIGN_PATH = os.path.join(BASE, "assets", "blender", "_work", "design.json")
MODEL_DIR = os.path.join(BASE, "src", "main", "resources", "assets", "veriborder", "models", "block")
BLENDER_DIR = os.path.join(BASE, "assets", "blender")

args = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
ONLY = None
if "--only" in args:
    ONLY = args[args.index("--only") + 1]

with open(DESIGN_PATH, "r", encoding="utf-8") as fh:
    DESIGN = json.load(fh)

DISPLAY = {
    "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
    "thirdperson_lefthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
    "firstperson_righthand": {"rotation": [0, 45, 0], "scale": [0.4, 0.4, 0.4]},
    "firstperson_lefthand": {"rotation": [0, 45, 0], "scale": [0.4, 0.4, 0.4]},
    "gui": {"rotation": [30, 225, 0], "scale": [0.625, 0.625, 0.625]},
    "ground": {"translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
    "fixed": {"rotation": [0, 180, 0], "scale": [0.5, 0.5, 0.5]},
}

EMISSION = {"led": 3.5, "screen": 2.0, "accent": 1.8}


def make_material(name, rgb01, kind):
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    nodes = mat.node_tree.nodes
    links = mat.node_tree.links
    nodes.clear()
    r, g, b = rgb01
    mat.diffuse_color = (r, g, b, 1.0)
    strength = EMISSION.get(kind, 0.0)
    if strength > 0:
        em = nodes.new(type='ShaderNodeEmission')
        em.inputs['Color'].default_value = (r, g, b, 1.0)
        em.inputs['Strength'].default_value = strength
        out = nodes.new(type='ShaderNodeOutputMaterial')
        links.new(em.outputs['Emission'], out.inputs['Surface'])
    else:
        bsdf = nodes.new(type='ShaderNodeBsdfPrincipled')
        bsdf.inputs['Base Color'].default_value = (r, g, b, 1.0)
        bsdf.inputs['Metallic'].default_value = 0.0
        bsdf.inputs['Roughness'].default_value = 0.7
        if 'Specular IOR Level' in bsdf.inputs:
            bsdf.inputs['Specular IOR Level'].default_value = 0.0
        elif 'Specular' in bsdf.inputs:
            bsdf.inputs['Specular'].default_value = 0.0
        out = nodes.new(type='ShaderNodeOutputMaterial')
        links.new(bsdf.outputs['BSDF'], out.inputs['Surface'])
    return mat


def add_cuboid(i, f, t, mat):
    cx = (f[0] + t[0]) / 2.0
    cy = (f[1] + t[1]) / 2.0
    cz = (f[2] + t[2]) / 2.0
    dx = t[0] - f[0]
    dy = t[1] - f[1]
    dz = t[2] - f[2]
    bpy.ops.mesh.primitive_cube_add(size=1, location=(cx, cy, cz))
    ob = bpy.context.object
    ob.name = "DEV_%02d_%s" % (i, mat.name)
    ob.scale = (dx, dy, dz)
    ob.data.materials.append(mat)
    return ob


def look_at(ob, target):
    loc = ob.location
    direction = mathutils.Vector(target) - loc
    ob.rotation_euler = direction.to_track_quat('-Z', 'Y').to_euler()


def add_area(name, loc, target, energy, size, color):
    bpy.ops.object.light_add(type='AREA', location=loc)
    light = bpy.context.object
    light.name = name
    light.data.energy = energy
    light.data.size = size
    light.data.color = color
    look_at(light, target)
    return light


def build_mc_json(dev_id, cuboids, slot_names):
    textures = {"particle": "veriborder:block/%s_body" % dev_id}
    for s in sorted(slot_names):
        textures[s] = "veriborder:block/%s_%s" % (dev_id, s)
    elements = []
    for cb in cuboids:
        f = cb["f"]
        t = cb["t"]
        s = cb["s"]
        frm = [round(f[0] * 16, 3), round(f[2] * 16, 3), round(f[1] * 16, 3)]
        to = [round(t[0] * 16, 3), round(t[2] * 16, 3), round(t[1] * 16, 3)]
        faces = {}
        for face in ("up", "down", "north", "south", "east", "west"):
            faces[face] = {"texture": "#" + s, "uv": [0, 0, 16, 16]}
        elements.append({"from": frm, "to": to, "faces": faces})
    return {"textures": textures, "elements": elements, "display": DISPLAY}


def process_device(dev_id):
    dev = DESIGN["devices"][dev_id]
    cuboids = dev["cuboids"]
    accent_rgb = dev["accent_rgb"]

    # slot -> (rgb01, kind)
    slot_defs = {}
    for s, info in DESIGN["shared_textures"].items():
        slot_defs[s] = ([c / 255.0 for c in info["rgb"]], info["kind"])
    slot_defs["accent"] = ([c / 255.0 for c in accent_rgb], "accent")

    # reset scene to empty factory state
    bpy.ops.wm.read_factory_settings(use_empty=True)
    scene = bpy.context.scene

    # materials (cache by slot)
    mats = {}
    for s, (rgb01, kind) in slot_defs.items():
        mats[s] = make_material("%s_%s" % (dev_id, s), rgb01, kind)

    # cuboids
    for i, cb in enumerate(cuboids):
        add_cuboid(i, cb["f"], cb["t"], mats[cb["s"]])

    # ground plane (not exported / not part of DEV_)
    bpy.ops.mesh.primitive_plane_add(size=8, location=(0.5, 0.34, -0.004))
    ground = bpy.context.object
    ground.name = "Ground"
    gmat = bpy.data.materials.new("ground")
    gmat.use_nodes = True
    gmat.node_tree.nodes["Principled BSDF"].inputs["Base Color"].default_value = (0.14, 0.15, 0.17, 1.0)
    gmat.node_tree.nodes["Principled BSDF"].inputs["Roughness"].default_value = 0.9
    ground.data.materials.append(gmat)

    # world background
    world = bpy.data.worlds.new("world") if bpy.data.worlds.get("world") is None else bpy.data.worlds["world"]
    scene.world = world
    world.use_nodes = True
    bg = world.node_tree.nodes.get("Background")
    if bg is not None:
        bg.inputs[0].default_value = (0.09, 0.10, 0.12, 1.0)

    # camera
    bpy.ops.object.camera_add(location=(0.5, -1.9, 0.74))
    cam = bpy.context.object
    cam.name = "Cam"
    cam.data.lens = 40
    look_at(cam, (0.5, 0.34, 0.44))
    scene.camera = cam

    # lights
    add_area("Key", (0.0, -1.8, 1.5), (0.5, 0.34, 0.45), 350.0, 1.2, (1.0, 0.97, 0.93))
    add_area("Fill", (1.6, -0.5, 0.8), (0.5, 0.34, 0.45), 90.0, 1.0, (0.85, 0.9, 1.0))
    add_area("Rim", (0.2, 1.9, 1.6), (0.5, 0.34, 0.45), 200.0, 0.8, (0.92, 0.95, 1.0))

    # render settings
    scene.render.engine = 'CYCLES'
    scene.render.resolution_x = 768
    scene.render.resolution_y = 768
    scene.render.film_transparent = False
    scene.render.image_settings.file_format = 'PNG'
    scene.render.image_settings.color_mode = 'RGB'
    try:
        scene.view_settings.view_transform = 'AgX'
    except Exception:
        pass
    try:
        scene.cycles.samples = 32
        scene.cycles.use_denoising = True
        scene.cycles.denoiser = 'OPENIMAGEDENOISE'
    except Exception:
        pass

    out_dir = os.path.join(BLENDER_DIR, dev_id)
    os.makedirs(out_dir, exist_ok=True)

    blend_path = os.path.join(out_dir, dev_id + ".blend")
    bpy.ops.wm.save_as_mainfile(filepath=blend_path)

    # OBJ export (only DEV_ meshes)
    bpy.ops.object.select_all(action='DESELECT')
    for ob in bpy.data.objects:
        if ob.type == 'MESH' and ob.name.startswith("DEV_"):
            ob.select_set(True)
    obj_path = os.path.join(out_dir, dev_id + ".obj")
    try:
        bpy.ops.wm.obj_export(filepath=obj_path, export_selected_objects=True, export_materials=True,
                              apply_modifiers=True, up_axis='Z', forward_axis='NEGATIVE_Y')
    except Exception:
        try:
            bpy.ops.wm.obj_export(filepath=obj_path, export_selected_objects=True, export_materials=True,
                                  apply_modifiers=True, up_axis='Z')
        except Exception:
            bpy.ops.wm.obj_export(filepath=obj_path, export_selected_objects=True, export_materials=True)

    # preview render
    preview_path = os.path.join(out_dir, "preview.png")
    scene.render.filepath = preview_path
    bpy.ops.render.render(write_still=True)

    # MC JSON
    used_slots = sorted(set(cb["s"] for cb in cuboids))
    model = build_mc_json(dev_id, cuboids, used_slots)
    model_path = os.path.join(MODEL_DIR, dev_id + ".json")
    with open(model_path, "w", encoding="utf-8") as fh:
        json.dump(model, fh, indent=2, ensure_ascii=True)
        fh.write("\n")

    print("OK %s: %d elements, blend=%s, obj=%s, preview=%s, json=%s" %
          (dev_id, len(cuboids), blend_path, obj_path, preview_path, model_path))


def main():
    ids = list(DESIGN["devices"].keys())
    if ONLY:
        ids = [i for i in ids if i == ONLY]
    for dev_id in ids:
        try:
            process_device(dev_id)
        except Exception as e:
            import traceback
            print("FAIL %s: %s" % (dev_id, e))
            traceback.print_exc()


main()
