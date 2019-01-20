package ch.unibas.dmi.dbis.cottontail

import ch.unibas.dmi.dbis.cottontail.config.Config
import ch.unibas.dmi.dbis.cottontail.database.schema.*
import ch.unibas.dmi.dbis.cottontail.knn.FloatVectorRow
import ch.unibas.dmi.dbis.cottontail.knn.FloatVectorRowSerializer
import ch.unibas.dmi.dbis.cottontail.knn.KnnContainer
import ch.unibas.dmi.dbis.cottontail.knn.metrics.Distance
import ch.unibas.dmi.dbis.cottontail.model.DatabaseException
import com.google.gson.GsonBuilder
import org.db.mapdb.list.LinkedListFactory

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths

import kotlin.concurrent.thread

object Playground {

    @JvmStatic
    fun main(args: Array<String>) {
        val path = args[0]
        Files.newBufferedReader(Paths.get(path)).use { reader ->
            val gson = GsonBuilder().create()
            val config = gson.fromJson(reader, Config::class.java)
            if (config != null) {
                loadAndPersist(config)
            }
        }
    }

    fun loadAndRead(config: Config) {
        try {
            val entity = Entity("test", Paths.get(config.dataFolder))
            val tx = entity.Tx(readonly = true)

            val block = {
                val start = System.currentTimeMillis()
                val container = KnnContainer(10000, FloatArray(512), Distance.L2)

                tx.forEachColumn({ l: Long, floats: FloatArray -> container.add(l,floats) }, ColumnType.specForName("feature","DFARRAY"))
                println(String.format("%s: kNN for n=%d, s=%d vectors took %d ms", Thread.currentThread().name, container.knn.size, tx.count(), System.currentTimeMillis() - start))
            }


            for (i in 1..1) {
                thread(start = true, block = block);
            }


        } catch (e: IOException) {
            e.printStackTrace()
        }

    }


    @Throws(IOException::class, DatabaseException::class)
    fun loadAndPersist(config: Config) {


        val entity = Entity.initialize("test", Paths.get(config.dataFolder), ColumnDef("id", StringColumnType()), ColumnDef("feature", FloatArrayColumnType()))
        val tx = entity.Tx(readonly = false)
        tx.execute {
            for (i in 1..76) {
                Files.newBufferedReader(Paths.get(String.format("/Volumes/Data (Mac)/Extracted/features_surfmf25k512_%d.json", i))).use { reader ->
                    val gson = GsonBuilder().create()
                    val features = gson.fromJson(reader, Array<Feature>::class.java)
                    val start = System.currentTimeMillis()
                    for (f in features) {
                        tx.insert(mapOf(
                                Pair(ColumnType.specForName("id","STRING"), f.id),
                                Pair(ColumnType.specForName("feature","DFARRAY"), f.feature)
                        ))
                    }
                    println(String.format("Writing %d vectors took %d ms", features.size, System.currentTimeMillis() - start))
                }
            }
            true
        }
        tx.close()
    }
}