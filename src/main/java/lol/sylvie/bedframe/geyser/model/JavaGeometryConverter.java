package lol.sylvie.bedframe.geyser.model;

import net.kyori.adventure.key.Key;
import net.minecraft.util.Pair;
import org.geysermc.pack.bedrock.resource.models.entity.ModelEntity;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.Geometry;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.geometry.Bones;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.geometry.Description;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.geometry.bones.Cubes;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.geometry.bones.cubes.Uv;
import org.geysermc.pack.bedrock.resource.models.entity.modelentity.geometry.bones.cubes.uv.*;
import team.unnamed.creative.base.Axis3D;
import team.unnamed.creative.base.CubeFace;
import team.unnamed.creative.model.Element;
import team.unnamed.creative.model.ElementFace;
import team.unnamed.creative.model.ElementRotation;
import team.unnamed.creative.model.Model;
import team.unnamed.creative.texture.TextureUV;

import java.util.*;

import static lol.sylvie.bedframe.util.BedframeConstants.LOGGER;

/*
 * Concepts here were inspired by:
 *  tomalbrc's fork: https://github.com/tomalbrc/bedframe/blob/main/src/main/java/lol/sylvie/bedframe/geyser/translator/JavaToBedrockGeometryTranslator.java
 *  Pack Converter's ModelConverter: https://github.com/GeyserMC/PackConverter/blob/master/converter/src/main/java/org/geysermc/pack/converter/converter/model/ModelConverter.java#L62
 * (I'm not using ModelConverter directly as it requires some resource pack boilerplate we don't need)
 */
public class JavaGeometryConverter {
    private static final String FORMAT_VERSION = "1.16.0";
    private static final String GEOMETRY_FORMAT = "geometry.%s";

    private static float[] javaPosToBedrock(float[] java) {
        // Bedrock's block-model space is mirrored on the X axis relative to Java (the same
        // reason the blockstate transformation inverts X/Y rotation, and the reason Hydraulic
        // notes "X is mirrored on Bedrock"). Mirroring X here keeps directional/asymmetric
        // blocks (stairs, trellises, lattices) facing the correct side; symmetric blocks are
        // unaffected. Used for both cube positions and rotation pivots so they stay aligned.
        return new float[] { 8.0f - java[0], java[1], java[2] - 8.0f };
    }

    private static void applyFaceUv(Uv uv, CubeFace cubeFace, float[] uvValue, float[] uvSize, String texture) {
        switch (cubeFace) {
            case UP -> {
                Up up = new Up();
                up.uv(uvValue);
                up.uvSize(uvSize);
                up.materialInstance(texture);
                uv.up(up);
            }
            case DOWN -> {
                Down down = new Down();
                down.uv(uvValue);
                down.uvSize(uvSize);
                down.materialInstance(texture);
                uv.down(down);
            }
            case NORTH -> {
                North north = new North();
                north.uv(uvValue);
                north.uvSize(uvSize);
                north.materialInstance(texture);
                uv.north(north);
            }
            case SOUTH -> {
                South south = new South();
                south.uv(uvValue);
                south.uvSize(uvSize);
                south.materialInstance(texture);
                uv.south(south);
            }
            case EAST -> {
                East east = new East();
                east.uv(uvValue);
                east.uvSize(uvSize);
                east.materialInstance(texture);
                uv.east(east);
            }
            case WEST -> {
                West west = new West();
                west.uv(uvValue);
                west.uvSize(uvSize);
                west.materialInstance(texture);
                uv.west(west);
            }
        }
    }

    /**
     * Builds one Bedrock cube (as its own bone) for a Java element spanning
     * {@code javaFrom..javaTo}. {@code vCropStart}/{@code vCropSize} are fractions in [0,1]
     * of each side face's vertical UV span to keep; they let a cube that was split at y=0
     * sample only its own slice of the texture (so the flower isn't duplicated on both
     * halves). Pass {@code (0f, 1f)} for an un-split element — that leaves the UV untouched.
     */
    private static void addElementCube(List<Bones> bones, String name,
                                       float[] javaFrom, float[] javaTo, Element element,
                                       float vCropStart, float vCropSize) {
        Bones bone = new Bones();
        bone.name(name);

        // Transform
        Cubes cube = new Cubes();
        // With X mirrored, the Bedrock min-X corner comes from javaTo (not javaFrom), so take
        // the min of the two mapped corners on X. Y/Z are unaffected by the mirror.
        float[] bedrockFrom = javaPosToBedrock(javaFrom);
        float[] bedrockTo = javaPosToBedrock(javaTo);
        float[] cubeOrigin = new float[] {
                Math.min(bedrockFrom[0], bedrockTo[0]),
                bedrockFrom[1],
                bedrockFrom[2] };
        float[] cubeSize = new float[] {
                javaTo[0] - javaFrom[0],
                javaTo[1] - javaFrom[1],
                javaTo[2] - javaFrom[2] };

        // Bedrock rejects the ENTIRE geometry (block then renders as the "?" missing-model
        // cube) if any cube has a zero-thickness dimension. Java models use such flat,
        // zero-thickness elements constantly for cross/plane shapes - flower-pot flowers,
        // open (down) blinds, picture frames, trellises, etc. Give any degenerate axis a
        // hair of thickness and recentre the cube on the original plane so its two large
        // faces still render the texture in place. Cubes with real volume are untouched.
        final float MIN_THICKNESS = 0.01f;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(cubeSize[i]) < MIN_THICKNESS) {
                cubeOrigin[i] -= MIN_THICKNESS / 2f;
                cubeSize[i] = MIN_THICKNESS;
            }
        }
        cube.origin(cubeOrigin);
        cube.size(cubeSize);
        cube.inflate(0f);

        // Rotation
        ElementRotation rotation = element.rotation();
        if (rotation != null) { // This can be null actually
            float[] rotOrigin = rotation.origin().toArray();
            bone.pivot(javaPosToBedrock(rotOrigin));

            // We are given an angle and an axis, and we need to provide a vector
            float rotValue = (360 - rotation.angle()) % 360;
            Axis3D axis = rotation.axis();
            float[] rotArray = new float[] {
                    axis == Axis3D.X ? rotValue : 0f,
                    axis == Axis3D.Y ? rotValue : 0f,
                    axis == Axis3D.Z ? rotValue : 0f
            };
            bone.rotation(rotArray);
        } else {
            // this might be default, not sure
            bone.pivot(new float[] { 0f, 0f, 0f });
        }

        // UV
        Uv uv = new Uv();
        Map<CubeFace, ElementFace> faceMap = element.faces();
        if (faceMap.isEmpty()) {
            faceMap = new HashMap<>();
            for (CubeFace face : CubeFace.values()) {
                faceMap.put(face, ElementFace.face().texture(face.name()).build());
            }
        }

        for (Map.Entry<CubeFace, ElementFace> faceEntry : faceMap.entrySet()) {
            CubeFace direction = faceEntry.getKey();
            ElementFace face = faceEntry.getValue();

            TextureUV textureUV = face.uv0();
            if (textureUV == null)
                textureUV = TextureUV.uv(0, 0, 16f, 16f);
            else textureUV = TextureUV.uv(textureUV.from().multiply(16f), textureUV.to().multiply(16f));

            float[] uvValue;
            float[] uvSize;
            if (direction.axis() == Axis3D.Y) {
                uvValue = new float[] { textureUV.to().x(), textureUV.to().y() };
                uvSize = new float[] { (textureUV.from().x() - uvValue[0]), (textureUV.from().y() - uvValue[1]) };
            } else {
                uvValue = new float[] { textureUV.from().x(), textureUV.from().y() };
                uvSize = new float[] { (textureUV.to().x() - uvValue[0]), (textureUV.to().y() - uvValue[1]) };
                // Keep only this piece's vertical slice of the texture when the element was
                // split at y=0. Side faces (non-Y) map their V span to the cube's height;
                // top/bottom caps (Y axis) don't, so they're left alone. With (0f, 1f) this
                // is a no-op, so un-split cubes are byte-for-byte identical to before.
                uvValue[1] = uvValue[1] + uvSize[1] * vCropStart;
                uvSize[1] = uvSize[1] * vCropSize;
            }

            // Mirror the face to match the X-mirrored geometry above. Faces that span the X
            // axis (north/south/up/down) keep their position but flip their horizontal (U)
            // texture coordinate; faces normal to X (east/west) swap to the opposite side.
            CubeFace outDirection = direction;
            switch (direction) {
                case NORTH, SOUTH, UP, DOWN -> {
                    uvValue[0] = uvValue[0] + uvSize[0];
                    uvSize[0] = -uvSize[0];
                }
                case EAST -> outDirection = CubeFace.WEST;
                case WEST -> outDirection = CubeFace.EAST;
            }

            applyFaceUv(uv, outDirection, uvValue, uvSize, face.texture().replace("#", ""));
        }

        cube.uv(uv);
        bone.cubes(List.of(cube));
        bone.textureMeshes(null);
        bones.add(bone);
    }

    public static Pair<String, ModelEntity> convert(Model model) {
        List<Element> elements = model.elements();
        if (elements.isEmpty()) {
            LOGGER.error("Model {} is empty :(", model.key());
            return null;
        }

        ModelEntity modelEntity = new ModelEntity();
        modelEntity.formatVersion(FORMAT_VERSION);

        Geometry geometry = new Geometry();
        List<Bones> bones = new ArrayList<>();

        int nthElement = 0;
        for (Element element : elements) {
            float[] javaFrom = element.from().toArray();
            float[] javaTo = element.to().toArray();

            // A custom-block cube whose geometry extends below the block floor (y<0) while
            // also reaching up into the block breaks Bedrock's sub-chunk mesh and makes the
            // nearby custom blocks vanish — Beautify's grown flower-pot flower is the case:
            // a 16x32 plane from y=-16 up to y=16. We clamp the below-floor part up to y=0 so
            // the cube sits inside the block. (Splitting it into a separate below-floor cube
            // to preserve the hanging part was tried and also breaks — Bedrock can't mesh
            // that large plane below the block regardless of how it's divided, so clamping
            // it away is the only thing that renders.) This only touches boundary-crossing
            // cubes; nothing that already renders is affected. Cosmetic cost: the part of
            // such geometry hanging below the block is clipped on Bedrock (the flower keeps
            // its bloom inside the block and loses the stem below).
            if (javaFrom[1] < 0f && javaTo[1] > 0f) {
                javaFrom[1] = 0f;
            }

            addElementCube(bones, "element_" + (nthElement++), javaFrom, javaTo, element, 0f, 1f);
        }

        geometry.bones(bones);
        modelEntity.geometry(List.of(geometry));

        String namespace = model.key().namespace();
        String[] pathSplit = model.key().value().split("/");
        String path = pathSplit[pathSplit.length - 1];
        String geometryName = String.format(GEOMETRY_FORMAT, (namespace.equals(Key.MINECRAFT_NAMESPACE) ? "" : namespace + ".") +
                path.replace(":", "."));

        Description description = new Description();
        description.identifier(geometryName);
        description.textureWidth(16);
        description.textureHeight(16);
        geometry.description(description);

        return new Pair<>(geometryName, modelEntity);
    }
}
