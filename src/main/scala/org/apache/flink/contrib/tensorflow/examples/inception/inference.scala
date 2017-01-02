package org.apache.flink.contrib.tensorflow.examples.inception

import java.nio.charset.StandardCharsets
import java.util

import org.apache.flink.api.common.functions.RichMapFunction
import org.apache.flink.configuration.Configuration
import org.apache.flink.contrib.tensorflow.common.TensorValue
import org.apache.flink.contrib.tensorflow.util.GraphUtils
import org.apache.flink.core.fs.Path
import org.slf4j.{Logger, LoggerFactory}
import org.tensorflow.{Graph, Session}

import scala.collection.JavaConverters._

/**
  * Infers labels for images.
  *
  * @param modelPath the directory containing the model files.
  */
class InceptionModel(modelPath: String)
  extends RichMapFunction[TensorValue,Inference] {

  protected val LOG: Logger = LoggerFactory.getLogger(classOf[InceptionModel])

  @transient var labels: List[String] = _
  @transient var graph: Graph = _
  @transient var session: Session = _

  override def open(parameters: Configuration): Unit = {
    super.open(parameters)
    labels = GraphUtils.readAllLines(
      new Path(modelPath, "imagenet_comp_graph_label_strings.txt"), StandardCharsets.UTF_8).asScala.toList
    graph = GraphUtils.importFromPath(
      new Path(modelPath, "tensorflow_inception_graph.pb"))

    session = new Session(graph)
  }


  override def close(): Unit = {
    session.close()
    graph.close()
    super.close()
  }


  override def map(value: TensorValue): Inference = {
    val cmd = session.runner().feed("input", value.toTensor).fetch("output")
    val result = cmd.run().get(0)

    val rshape = result.shape
    if (result.numDimensions != 2 || rshape(0) != 1)
      throw new RuntimeException(String.format("Expected model to produce a [1 N] shaped tensor where N is the number of labels, instead it produced one with shape %s", util.Arrays.toString(rshape)))
    val nlabels = rshape(1).toInt
    val inferenceMatrix = Array.ofDim[Float](1,nlabels)
    result.copyTo(inferenceMatrix)

    val inference = toInference(inferenceMatrix)
    LOG.info(s"LabelImage($value) => $inference")
    inference
  }

  private def toInference(inferenceMatrix: Array[Array[Float]]): Inference = {
    Inference(inferenceMatrix(0).toList.zip(labels).sortWith(_._1 > _._1).take(5))
  }
}

case class Inference(inferences: List[(Float,String)])

