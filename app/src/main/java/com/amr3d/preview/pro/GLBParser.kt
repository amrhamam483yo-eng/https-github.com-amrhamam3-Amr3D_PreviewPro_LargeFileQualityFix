package com.amr3d.preview.pro

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class GLBParseException(message: String) : Exception(message)

// ═══ جديد: data class للمادة المستخرجة من glTF ═══
data class GLBResolvedMaterial(
    val baseColorFactor: FloatArray = floatArrayOf(1f, 1f, 1f, 1f),
    val metallicFactor: Float = 0f,
    val roughnessFactor: Float = 1f,
    val baseColorTextureIndex: Int = -1
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as GLBResolvedMaterial
        if (!baseColorFactor.contentEquals(other.baseColorFactor)) return false
        if (metallicFactor != other.metallicFactor) return false
        if (roughnessFactor != other.roughnessFactor) return false
        if (baseColorTextureIndex != other.baseColorTextureIndex) return false
        return true
    }
    override fun hashCode(): Int {
        var result = baseColorFactor.contentHashCode()
        result = 31 * result + metallicFactor.hashCode()
        result = 31 * result + roughnessFactor.hashCode()
        result = 31 * result + baseColorTextureIndex
        return result
    }
}

// ═══ جديد: نتيجة القراءة الكاملة (موديل + مواد + indices) ═══
data class GLBParseResult(
    val stlModel: STLModel,
    val materials: List<GLBResolvedMaterial>,
    val triangleMaterialIndices: IntArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as GLBParseResult
        if (stlModel != other.stlModel) return false
        if (materials != other.materials) return false
        if (!triangleMaterialIndices.contentEquals(other.triangleMaterialIndices)) return false
        return true
    }
    override fun hashCode(): Int {
        var result = stlModel.hashCode()
        result = 31 * result + materials.hashCode()
        result = 31 * result + triangleMaterialIndices.contentHashCode()
        return result
    }
}

object GLBParser {

    private const val MAGIC = 0x46546C67 // "glTF" كـ uint32 little-endian
    private const val CHUNK_TYPE_JSON = 0x4E4F534A
    private const val CHUNK_TYPE_BIN = 0x004E4942

    private fun maxGlbFileBytes(): Long {
        val maxHeap = Runtime.getRuntime().maxMemory()
        return (maxHeap * 0.35).toLong().coerceAtLeast(50_000_000L)
    }

    private fun safeTriangleCap(): Int {
        val maxHeapBytes = Runtime.getRuntime().maxMemory()
        val budgetBytes = (maxHeapBytes * 0.18).toLong()
        val cap = budgetBytes / 72L
        return cap.coerceIn(250_000L, 4_000_000L).toInt()
    }

    // ═══ القديمة: نفس الـ signature بالظبط — backward compatibility ═══
    fun parse(context: Context, uri: Uri, onProgress: (Int) -> Unit = {}): STLModel {
        return parseWithMaterials(context, uri, onProgress).stlModel
    }

    // ═══ جديد: parse كامل مع استخراج المواد ═══
    fun parseWithMaterials(context: Context, uri: Uri, onProgress: (Int) -> Unit = {}): GLBParseResult {
        val resolver = context.contentResolver

        val fileSize: Long = resolver.query(
            uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (idx >= 0 && !c.isNull(idx)) c.getLong(idx) else -1L
            } else -1L
        } ?: -1L

        if (fileSize > 0 && fileSize > maxGlbFileBytes()) {
            throw GLBParseException(context.getString(R.string.error_glb_too_large))
        }

        onProgress(5)
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw GLBParseException(context.getString(R.string.error_glb_read_failed))
        onProgress(20)

        if (bytes.size < 12) throw GLBParseException(context.getString(R.string.error_glb_corrupt))

        val header = ByteBuffer.wrap(bytes, 0, 12).order(ByteOrder.LITTLE_ENDIAN)
        val magic = header.int
        @Suppress("UNUSED_VARIABLE") val version = header.int
        val totalLength = header.int
        if (magic != MAGIC) throw GLBParseException(context.getString(R.string.error_glb_not_glb))
        if (totalLength > bytes.size + 8) throw GLBParseException(context.getString(R.string.error_glb_corrupt))

        var jsonBytes: ByteArray? = null
        var binBytes: ByteArray? = null
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val chunkHeader = ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN)
            val chunkLength = chunkHeader.int
            val chunkType = chunkHeader.int
            val dataStart = offset + 8
            if (dataStart + chunkLength > bytes.size) break
            when (chunkType) {
                CHUNK_TYPE_JSON -> jsonBytes = bytes.copyOfRange(dataStart, dataStart + chunkLength)
                CHUNK_TYPE_BIN -> binBytes = bytes.copyOfRange(dataStart, dataStart + chunkLength)
            }
            offset = dataStart + chunkLength
        }
        val json = jsonBytes ?: throw GLBParseException(context.getString(R.string.error_glb_no_json))
        onProgress(30)

        val root = JSONObject(String(json, Charsets.UTF_8))
        val accessors = root.optJSONArray("accessors") ?: JSONArray()
        val bufferViews = root.optJSONArray("bufferViews") ?: JSONArray()
        val buffersJson = root.optJSONArray("buffers") ?: JSONArray()
        val meshes = root.optJSONArray("meshes") ?: JSONArray()
        val nodes = root.optJSONArray("nodes") ?: JSONArray()
        val scenesArr = root.optJSONArray("scenes")
        val sceneIndex = root.optInt("scene", 0)

        val resolvedBuffers = Array(buffersJson.length()) { i ->
            val b = buffersJson.getJSONObject(i)
            val uriStr = b.optString("uri", "")
            when {
                uriStr.isEmpty() -> binBytes
                uriStr.startsWith("data:") -> {
                    val base64Part = uriStr.substringAfter("base64,", "")
                    if (base64Part.isNotEmpty()) android.util.Base64.decode(base64Part, android.util.Base64.DEFAULT) else null
                }
                else -> null
            }
        }

        // ═══ جديد: قراءة المواد من glTF JSON ═══
        val materialsList = ArrayList<GLBResolvedMaterial>()
        val materialsJson = root.optJSONArray("materials")
        if (materialsJson != null) {
            for (i in 0 until materialsJson.length()) {
                val mat = materialsJson.getJSONObject(i)
                val pbr = mat.optJSONObject("pbrMetallicRoughness")
                val baseColorFactor = if (pbr != null && pbr.has("baseColorFactor")) {
                    val arr = pbr.getJSONArray("baseColorFactor")
                    floatArrayOf(
                        arr.optDouble(0, 1.0).toFloat(),
                        arr.optDouble(1, 1.0).toFloat(),
                        arr.optDouble(2, 1.0).toFloat(),
                        arr.optDouble(3, 1.0).toFloat()
                    )
                } else floatArrayOf(1f, 1f, 1f, 1f)
                val metallicFactor = pbr?.optDouble("metallicFactor", 0.0)?.toFloat() ?: 0f
                val roughnessFactor = pbr?.optDouble("roughnessFactor", 1.0)?.toFloat() ?: 1f
                val baseColorTextureIndex = pbr?.optJSONObject("baseColorTexture")?.optInt("index", -1) ?: -1
                materialsList.add(GLBResolvedMaterial(baseColorFactor, metallicFactor, roughnessFactor, baseColorTextureIndex))
            }
        }

        fun componentSize(componentType: Int): Int = when (componentType) {
            5120, 5121 -> 1
            5122, 5123 -> 2
            5125, 5126 -> 4
            else -> 4
        }
        fun typeComponentCount(type: String): Int = when (type) {
            "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4
            "MAT4" -> 16; else -> 1
        }

        fun readFloatAccessor(accessorIdx: Int): FloatArray? {
            if (accessorIdx < 0 || accessorIdx >= accessors.length()) return null
            val acc = accessors.getJSONObject(accessorIdx)
            val bvIdx = acc.optInt("bufferView", -1)
            if (bvIdx < 0 || bvIdx >= bufferViews.length()) return null
            val bv = bufferViews.getJSONObject(bvIdx)
            val bufIdx = bv.optInt("buffer", 0)
            val buf = resolvedBuffers.getOrNull(bufIdx) ?: return null

            val count = acc.optInt("count", 0)
            val componentType = acc.optInt("componentType", 5126)
            val type = acc.optString("type", "VEC3")
            val numComp = typeComponentCount(type)
            val compSize = componentSize(componentType)
            val elementSize = numComp * compSize

            val bvOffset = bv.optInt("byteOffset", 0)
            val accOffset = acc.optInt("byteOffset", 0)
            val stride = if (bv.has("byteStride")) bv.getInt("byteStride") else elementSize
            val baseOffset = bvOffset + accOffset

            val out = FloatArray(count * numComp)
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) {
                val elemStart = baseOffset + i * stride
                for (c in 0 until numComp) {
                    val pos = elemStart + c * compSize
                    if (pos + compSize > buf.size) return out
                    out[i * numComp + c] = when (componentType) {
                        5126 -> bb.getFloat(pos)
                        5125 -> bb.getInt(pos).toFloat()
                        5123 -> (bb.getShort(pos).toInt() and 0xFFFF).toFloat()
                        5122 -> bb.getShort(pos).toFloat()
                        5121 -> (buf[pos].toInt() and 0xFF).toFloat()
                        5120 -> buf[pos].toFloat()
                        else -> 0f
                    }
                }
            }
            return out
        }

        fun readIndexAccessor(accessorIdx: Int): IntArray? {
            if (accessorIdx < 0 || accessorIdx >= accessors.length()) return null
            val acc = accessors.getJSONObject(accessorIdx)
            val bvIdx = acc.optInt("bufferView", -1)
            if (bvIdx < 0 || bvIdx >= bufferViews.length()) return null
            val bv = bufferViews.getJSONObject(bvIdx)
            val bufIdx = bv.optInt("buffer", 0)
            val buf = resolvedBuffers.getOrNull(bufIdx) ?: return null

            val count = acc.optInt("count", 0)
            val componentType = acc.optInt("componentType", 5123)
            val compSize = componentSize(componentType)
            val bvOffset = bv.optInt("byteOffset", 0)
            val accOffset = acc.optInt("byteOffset", 0)
            val stride = if (bv.has("byteStride")) bv.getInt("byteStride") else compSize
            val baseOffset = bvOffset + accOffset

            val out = IntArray(count)
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) {
                val pos = baseOffset + i * stride
                if (pos + compSize > buf.size) return out
                out[i] = when (componentType) {
                    5125 -> bb.getInt(pos)
                    5123 -> bb.getShort(pos).toInt() and 0xFFFF
                    5121 -> buf[pos].toInt() and 0xFF
                    else -> 0
                }
            }
            return out
        }

        fun identity4(): DoubleArray = doubleArrayOf(
            1.0,0.0,0.0,0.0, 0.0,1.0,0.0,0.0, 0.0,0.0,1.0,0.0, 0.0,0.0,0.0,1.0
        )
        fun mul4(a: DoubleArray, b: DoubleArray): DoubleArray {
            val r = DoubleArray(16)
            for (col in 0 until 4) for (row in 0 until 4) {
                var s = 0.0
                for (k in 0 until 4) s += a[k * 4 + row] * b[col * 4 + k]
                r[col * 4 + row] = s
            }
            return r
        }
        fun trsToMatrix(node: JSONObject): DoubleArray {
            if (node.has("matrix")) {
                val m = node.getJSONArray("matrix")
                return DoubleArray(16) { m.getDouble(it) }
            }
            val t = node.optJSONArray("translation")
            val tx = t?.optDouble(0, 0.0) ?: 0.0; val ty = t?.optDouble(1, 0.0) ?: 0.0; val tz = t?.optDouble(2, 0.0) ?: 0.0
            val s = node.optJSONArray("scale")
            val sx = s?.optDouble(0, 1.0) ?: 1.0; val sy = s?.optDouble(1, 1.0) ?: 1.0; val sz = s?.optDouble(2, 1.0) ?: 1.0
            val q = node.optJSONArray("rotation")
            val qx = q?.optDouble(0, 0.0) ?: 0.0; val qy = q?.optDouble(1, 0.0) ?: 0.0
            val qz = q?.optDouble(2, 0.0) ?: 0.0; val qw = q?.optDouble(3, 1.0) ?: 1.0

            val xx = qx*qx; val yy = qy*qy; val zz = qz*qz
            val xy = qx*qy; val xz = qx*qz; val yz = qy*qz
            val wx = qw*qx; val wy = qw*qy; val wz = qw*qz
            val r00 = 1-2*(yy+zz); val r01 = 2*(xy-wz);   val r02 = 2*(xz+wy)
            val r10 = 2*(xy+wz);   val r11 = 1-2*(xx+zz); val r12 = 2*(yz-wx)
            val r20 = 2*(xz-wy);   val r21 = 2*(yz+wx);   val r22 = 1-2*(xx+yy)

            return doubleArrayOf(
                r00*sx, r10*sx, r20*sx, 0.0,
                r01*sy, r11*sy, r21*sy, 0.0,
                r02*sz, r12*sz, r22*sz, 0.0,
                tx, ty, tz, 1.0
            )
        }
        fun applyPoint(m: DoubleArray, x: Float, y: Float, z: Float): FloatArray {
            val rx = m[0]*x + m[4]*y + m[8]*z + m[12]
            val ry = m[1]*x + m[5]*y + m[9]*z + m[13]
            val rz = m[2]*x + m[6]*y + m[10]*z + m[14]
            return floatArrayOf(rx.toFloat(), ry.toFloat(), rz.toFloat())
        }
        fun applyDir(m: DoubleArray, x: Float, y: Float, z: Float): FloatArray {
            val rx = m[0]*x + m[4]*y + m[8]*z
            val ry = m[1]*x + m[5]*y + m[9]*z
            val rz = m[2]*x + m[6]*y + m[10]*z
            val len = sqrt(rx*rx + ry*ry + rz*rz)
            return if (len > 1e-9) floatArrayOf((rx/len).toFloat(), (ry/len).toFloat(), (rz/len).toFloat())
                   else floatArrayOf(0f, 1f, 0f)
        }

        val maxTriangles = safeTriangleCap()
        var triangleCounter = 0L
        var keptCount = 0
        var estimatedTotalTriangles = 0L
        for (mi in 0 until meshes.length()) {
            val prims = meshes.getJSONObject(mi).optJSONArray("primitives") ?: continue
            for (pi in 0 until prims.length()) {
                val prim = prims.getJSONObject(pi)
                if (prim.optInt("mode", 4) != 4) continue
                val idxAcc = prim.optInt("indices", -1)
                val count = if (idxAcc >= 0 && idxAcc < accessors.length())
                    accessors.getJSONObject(idxAcc).optInt("count", 0)
                else {
                    val posAcc = prim.optJSONObject("attributes")?.optInt("POSITION", -1) ?: -1
                    if (posAcc >= 0 && posAcc < accessors.length()) accessors.getJSONObject(posAcc).optInt("count", 0) else 0
                }
                estimatedTotalTriangles += count / 3
            }
        }
        val stride = if (estimatedTotalTriangles > maxTriangles)
            Math.ceil(estimatedTotalTriangles.toDouble() / maxTriangles).toInt()
        else 1

        val outVerts = ArrayList<Float>(minOf(3_000_000, maxTriangles * 9))
        val outNorms = ArrayList<Float>(minOf(3_000_000, maxTriangles * 9))
        // ═══ جديد: قائمة material index لكل مثلث محتفظ ═══
        val triangleMaterialIndicesList = ArrayList<Int>(minOf(estimatedTotalTriangles.toInt(), maxTriangles).coerceAtLeast(16))

        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE

        // ═══ جديد: استقبال materialIdx ═══
        fun processPrimitive(prim: JSONObject, worldMatrix: DoubleArray, materialIdx: Int) {
            if (prim.optInt("mode", 4) != 4) return
            val attrs = prim.optJSONObject("attributes") ?: return
            val posAccIdx = attrs.optInt("POSITION", -1)
            if (posAccIdx < 0) return
            val rawPositions = readFloatAccessor(posAccIdx) ?: return
            val normAccIdx = attrs.optInt("NORMAL", -1)
            val rawNormals = if (normAccIdx >= 0) readFloatAccessor(normAccIdx) else null

            val vertexCount = rawPositions.size / 3
            val worldPos = Array(vertexCount) { i ->
                applyPoint(worldMatrix, rawPositions[i*3], rawPositions[i*3+1], rawPositions[i*3+2])
            }
            val worldNorm = if (rawNormals != null) Array(vertexCount) { i ->
                applyDir(worldMatrix, rawNormals[i*3], rawNormals[i*3+1], rawNormals[i*3+2])
            } else null

            for (p in worldPos) {
                if (p[0] < minX) minX = p[0]; if (p[1] < minY) minY = p[1]; if (p[2] < minZ) minZ = p[2]
                if (p[0] > maxX) maxX = p[0]; if (p[1] > maxY) maxY = p[1]; if (p[2] > maxZ) maxZ = p[2]
            }

            val indices = readIndexAccessor(prim.optInt("indices", -1))
            val triCount = (indices?.size ?: vertexCount) / 3

            for (t in 0 until triCount) {
                val keepThis = (triangleCounter % stride == 0L) && keptCount < maxTriangles
                if (keepThis) {
                    val i0: Int; val i1: Int; val i2: Int
                    if (indices != null) {
                        i0 = indices[t*3]; i1 = indices[t*3+1]; i2 = indices[t*3+2]
                    } else {
                        i0 = t*3; i1 = t*3+1; i2 = t*3+2
                    }
                    if (i0 < vertexCount && i1 < vertexCount && i2 < vertexCount) {
                        val pa = worldPos[i0]; val pb = worldPos[i1]; val pc = worldPos[i2]
                        val na = worldNorm?.get(i0); val nb = worldNorm?.get(i1); val nc = worldNorm?.get(i2)
                        val (fa, fb, fc) = if (na != null && nb != null && nc != null) Triple(na, nb, nc)
                            else { val n = computeFaceNormal(pa, pb, pc); Triple(n, n, n) }

                        outVerts.add(pa[0]); outVerts.add(pa[1]); outVerts.add(pa[2])
                        outVerts.add(pb[0]); outVerts.add(pb[1]); outVerts.add(pb[2])
                        outVerts.add(pc[0]); outVerts.add(pc[1]); outVerts.add(pc[2])
                        outNorms.add(fa[0]); outNorms.add(fa[1]); outNorms.add(fa[2])
                        outNorms.add(fb[0]); outNorms.add(fb[1]); outNorms.add(fb[2])
                        outNorms.add(fc[0]); outNorms.add(fc[1]); outNorms.add(fc[2])
                        triangleMaterialIndicesList.add(materialIdx) // ═══ جديد ═══
                        keptCount++
                    }
                }
                triangleCounter++
            }
        }

        fun walkNode(nodeIdx: Int, parentMatrix: DoubleArray) {
            if (nodeIdx < 0 || nodeIdx >= nodes.length()) return
            val node = nodes.getJSONObject(nodeIdx)
            val world = mul4(parentMatrix, trsToMatrix(node))
            val meshIdx = node.optInt("mesh", -1)
            if (meshIdx >= 0 && meshIdx < meshes.length()) {
                val prims = meshes.getJSONObject(meshIdx).optJSONArray("primitives")
                if (prims != null) for (pi in 0 until prims.length()) {
                    val prim = prims.getJSONObject(pi)
                    val matIdx = prim.optInt("material", -1) // ═══ جديد ═══
                    processPrimitive(prim, world, matIdx)
                }
            }
            val children = node.optJSONArray("children")
            if (children != null) for (ci in 0 until children.length()) walkNode(children.getInt(ci), world)
        }

        val rootNodeIndices = ArrayList<Int>()
        if (scenesArr != null && sceneIndex < scenesArr.length()) {
            val sceneNodes = scenesArr.getJSONObject(sceneIndex).optJSONArray("nodes")
            if (sceneNodes != null) for (i in 0 until sceneNodes.length()) rootNodeIndices.add(sceneNodes.getInt(i))
        } else {
            for (i in 0 until nodes.length()) rootNodeIndices.add(i)
        }
        onProgress(40)
        for (rootIdx in rootNodeIndices) walkNode(rootIdx, identity4())
        onProgress(90)

        if (keptCount == 0) {
            throw GLBParseException(context.getString(R.string.error_glb_no_geometry))
        }

        // ═══ جديد: نرجع GLBParseResult كامل ═══
        return GLBParseResult(
            stlModel = STLModel(
                vertices = outVerts.toFloatArray(),
                normals = outNorms.toFloatArray(),
                triangleCount = keptCount,
                minBounds = floatArrayOf(minX, minY, minZ),
                maxBounds = floatArrayOf(maxX, maxY, maxZ),
                estimatedOriginalTriangleCount = triangleCounter.toInt(),
                isApproximate = stride > 1,
                isWatertightHint = (keptCount % 2 == 0)
            ),
            materials = materialsList,
            triangleMaterialIndices = triangleMaterialIndicesList.toIntArray()
        )
    }

    private fun computeFaceNormal(a: FloatArray, b: FloatArray, c: FloatArray): FloatArray {
        val ux = b[0]-a[0]; val uy = b[1]-a[1]; val uz = b[2]-a[2]
        val vx = c[0]-a[0]; val vy = c[1]-a[1]; val vz = c[2]-a[2]
        var nx = uy*vz - uz*vy
        var ny = uz*vx - ux*vz
        var nz = ux*vy - uy*vx
        val len = sqrt(nx*nx + ny*ny + nz*nz)
        if (len > 1e-12f) { nx /= len; ny /= len; nz /= len }
        return floatArrayOf(nx, ny, nz)
    }
}
