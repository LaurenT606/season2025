package frc.robot.subsystems.vision;

import static frc.robot.GlobalConstants.FieldMap.APRIL_TAG_FIELD_LAYOUT;
import static frc.robot.subsystems.vision.apriltagvision.AprilTagVisionConstants.*;
import static frc.robot.subsystems.vision.apriltagvision.AprilTagVisionHelpers.generateDynamicStdDevs;

import edu.wpi.first.math.Matrix;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Pose3d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.numbers.N1;
import edu.wpi.first.math.numbers.N3;
import edu.wpi.first.wpilibj.Alert;
import edu.wpi.first.wpilibj2.command.SubsystemBase;
import java.util.LinkedList;
import java.util.List;
import org.littletonrobotics.junction.Logger;

public class Vision extends SubsystemBase {
  private final VisionConsumer consumer;
  private final VisionIO[] ios;
  private final VisionIOInputsAutoLogged[] inputs;
  private final Alert[] disconnectedAlerts;

  public Vision(VisionConsumer consumer, VisionIO... ios) {
    this.consumer = consumer;
    this.ios = ios;

    // Initialize inputs
    this.inputs = new VisionIOInputsAutoLogged[ios.length];
    for (int i = 0; i < inputs.length; i++) {
      inputs[i] = new VisionIOInputsAutoLogged();
    }

    // Initialize disconnected alerts
    this.disconnectedAlerts = new Alert[ios.length];
    for (int i = 0; i < inputs.length; i++) {
      disconnectedAlerts[i] =
          new Alert(
              "Vision camera " + Integer.toString(i) + " is disconnected.",
              Alert.AlertType.kWarning);
    }
  }

  /**
   * Returns the yaw to the best target, which can be used for vision-based object or tag tracking.
   *
   * @param cameraIndex The index of the camera to use.
   */
  public Rotation2d getTargetX(int cameraIndex) {
    return inputs[cameraIndex].latestTargetObservation.tx();
  }

  /**
   * Returns the pitch to the best target, which can be used for vision-based object or tag
   * tracking.
   *
   * @param cameraIndex The index of the camera to use.
   */
  public Rotation2d getTargetY(int cameraIndex) {
    return inputs[cameraIndex].latestTargetObservation.ty();
  }

  @Override
  public void periodic() {
    for (int i = 0; i < ios.length; i++) {
      ios[i].updateInputs(inputs[i]);
      Logger.processInputs("AprilTagVision/Camera" + i, inputs[i]);
    }

    // Initialize logging values
    List<Pose3d> allTagPoses = new LinkedList<>();
    List<Pose3d> allRobotPoses = new LinkedList<>();
    List<Pose3d> allRobotPosesAccepted = new LinkedList<>();
    List<Pose3d> allRobotPosesRejected = new LinkedList<>();

    // Loop over cameras
    for (int cameraIndex = 0; cameraIndex < ios.length; cameraIndex++) {
      // Update disconnected alert
      disconnectedAlerts[cameraIndex].set(!inputs[cameraIndex].connected);

      // Initialize logging values
      List<Pose3d> tagPoses = new LinkedList<>();
      List<Pose3d> robotPoses = new LinkedList<>();
      List<Pose3d> robotPosesAccepted = new LinkedList<>();
      List<Pose3d> robotPosesRejected = new LinkedList<>();

      // Add tag poses
      for (int tagId : inputs[cameraIndex].tagIds) {
        var tagPose = APRIL_TAG_FIELD_LAYOUT.getTagPose(tagId);
        if (tagPose.isPresent()) {
          tagPoses.add(tagPose.get());
        }
      }

      // Loop over pose observations
      for (var observation : inputs[cameraIndex].poseObservations) {
        // Check whether to reject pose
        boolean rejectPose =
            observation.tagCount() == 0 // Must have at least one tag
                || (observation.tagCount() == 1
                    && observation.ambiguity()
                        > MAX_AMBIGUITY_CUTOFF) // Cannot be high ambiguity on single tag
                || Math.abs(observation.pose().getZ())
                    > MAX_Z_ERROR // Must have realistic Z coordinate
                //                || observation.averageTagDistance() > MAX_DISTANCE_CUTOFF
                // Must be within the field boundaries
                || observation.pose().getX() < 0.0
                || observation.pose().getX() > APRIL_TAG_FIELD_LAYOUT.getFieldLength()
                || observation.pose().getY() < 0.0
                || observation.pose().getY() > APRIL_TAG_FIELD_LAYOUT.getFieldWidth();

        // Add pose to log
        robotPoses.add(observation.pose());
        if (rejectPose) {
          robotPosesRejected.add(observation.pose());
        } else {
          robotPosesAccepted.add(observation.pose());
        }

        // Skip if rejected
        if (rejectPose) {
          continue;
        }

        // Send vision observation
        consumer.accept(
            observation.pose().toPose2d(),
            observation.timestamp(),
            generateDynamicStdDevs(observation, cameraIndex));
      }

      // Log camera datadata
      Logger.recordOutput(
          "AprilTagVision/Camera" + cameraIndex + "/TagPoses",
          tagPoses.toArray(new Pose3d[tagPoses.size()]));
      Logger.recordOutput(
          "AprilTagVision/Camera" + cameraIndex + "/RobotPoses",
          robotPoses.toArray(new Pose3d[robotPoses.size()]));
      Logger.recordOutput(
          "AprilTagVision/Camera" + cameraIndex + "/RobotPosesAccepted",
          robotPosesAccepted.toArray(new Pose3d[robotPosesAccepted.size()]));
      Logger.recordOutput(
          "AprilTagVision/Camera" + cameraIndex + "/RobotPosesRejected",
          robotPosesRejected.toArray(new Pose3d[robotPosesRejected.size()]));
      allTagPoses.addAll(tagPoses);
      allRobotPoses.addAll(robotPoses);
      allRobotPosesAccepted.addAll(robotPosesAccepted);
      allRobotPosesRejected.addAll(robotPosesRejected);
    }

    // Log summary data
    Logger.recordOutput(
        "AprilTagVision/Summary/TagPoses", allTagPoses.toArray(new Pose3d[allTagPoses.size()]));
    Logger.recordOutput(
        "AprilTagVision/Summary/RobotPoses",
        allRobotPoses.toArray(new Pose3d[allRobotPoses.size()]));
    Logger.recordOutput(
        "AprilTagVision/Summary/RobotPosesAccepted",
        allRobotPosesAccepted.toArray(new Pose3d[allRobotPosesAccepted.size()]));
    Logger.recordOutput(
        "AprilTagVision/Summary/RobotPosesRejected",
        allRobotPosesRejected.toArray(new Pose3d[allRobotPosesRejected.size()]));
  }

  @FunctionalInterface
  public interface VisionConsumer {
    void accept(
        Pose2d visionRobotPoseMeters,
        double timestampSeconds,
        Matrix<N3, N1> visionMeasurementStdDevs);
  }
}
