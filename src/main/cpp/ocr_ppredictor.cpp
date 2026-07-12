//
// Created by fujiayi on 2020/7/1.
//

#include "ocr_ppredictor.h"
#include "common.h"
#include "ocr_cls_process.h"
#include "ocr_crnn_process.h"
#include "ocr_db_post_process.h"
#include "preprocess.h"

namespace ppredictor {

OCR_PPredictor::OCR_PPredictor(const OCR_Config &config) : _config(config) {}

int OCR_PPredictor::init(const std::string &det_model_content,
                         const std::string &rec_model_content,
                         const std::string &cls_model_content) {
  _det_predictor = std::unique_ptr<PPredictor>(new PPredictor{
      _config.use_opencl, _config.thread_num, NET_OCR, _config.mode});
  _det_predictor->init_nb(det_model_content);

  _rec_predictor = std::unique_ptr<PPredictor>(new PPredictor{
      _config.use_opencl, _config.thread_num, NET_OCR_INTERNAL, _config.mode});
  _rec_predictor->init_nb(rec_model_content);

  _cls_predictor = std::unique_ptr<PPredictor>(new PPredictor{
      _config.use_opencl, _config.thread_num, NET_OCR_INTERNAL, _config.mode});
  _cls_predictor->init_nb(cls_model_content);
  return RETURN_OK;
}

int OCR_PPredictor::init_from_file(const std::string &det_model_path,
                                   const std::string &rec_model_path,
                                   const std::string &cls_model_path) {
  _det_predictor = std::unique_ptr<PPredictor>(new PPredictor{
      _config.use_opencl, _config.thread_num, NET_OCR, _config.mode});
  _det_predictor->init_from_file(det_model_path);

  _rec_predictor = std::unique_ptr<PPredictor>(new PPredictor{
      _config.use_opencl, _config.thread_num, NET_OCR_INTERNAL, _config.mode});
  _rec_predictor->init_from_file(rec_model_path);

  _cls_predictor = std::unique_ptr<PPredictor>(new PPredictor{
      _config.use_opencl, _config.thread_num, NET_OCR_INTERNAL, _config.mode});
  _cls_predictor->init_from_file(cls_model_path);
  return RETURN_OK;
}
/**
 * for debug use, show result of First Step
 * @param filter_boxes
 * @param boxes
 * @param srcimg
 */
static void
visual_img(const std::vector<std::vector<std::vector<int>>> &filter_boxes,
           const std::vector<std::vector<std::vector<int>>> &boxes,
           const cv::Mat &srcimg) {
  // visualization
  std::vector<std::array<cv::Point, 4>> rook_points(filter_boxes.size());
  for (int n = 0; n < static_cast<int>(filter_boxes.size()); n++) {
    int point_count = std::min(4, static_cast<int>(filter_boxes[n].size()));
    for (int m = 0; m < point_count; m++) {
      rook_points[n][m] =
          cv::Point(int(filter_boxes[n][m][0]), int(filter_boxes[n][m][1]));
    }
  }

  cv::Mat img_vis;
  srcimg.copyTo(img_vis);
  int draw_count = std::min(
      static_cast<int>(boxes.size()),
      static_cast<int>(rook_points.size()));
  for (int n = 0; n < draw_count; n++) {
    const cv::Point *ppt[1] = {rook_points[n].data()};
    int npt[] = {4};
    cv::polylines(img_vis, ppt, npt, 1, 1, CV_RGB(0, 255, 0), 2, 8, 0);
  }
  // 调试用，自行替换需要修改的路径
  cv::imwrite("/sdcard/1/vis.png", img_vis);
}

std::vector<OCRPredictResult>
OCR_PPredictor::infer_ocr(cv::Mat &origin, int max_size_len, int run_det,
                          int run_cls, int run_rec) {
  LOGI("ocr cpp start *****************");
  LOGI("ocr cpp det: %d, cls: %d, rec: %d", run_det, run_cls, run_rec);
  std::vector<OCRPredictResult> ocr_results;
  if (run_det) {
    infer_det(origin, max_size_len, ocr_results);
  }
  if (run_rec) {
    if (ocr_results.size() == 0) {
      OCRPredictResult res;
      ocr_results.emplace_back(std::move(res));
    }
    for (int i = 0; i < ocr_results.size(); i++) {
      infer_rec(origin, run_cls, ocr_results[i]);
    }
  } else if (run_cls) {
    ClsPredictResult cls_res = infer_cls(origin);
    OCRPredictResult res;
    res.cls_score = cls_res.cls_score;
    res.cls_label = cls_res.cls_label;
    ocr_results.push_back(res);
  }

  LOGI("ocr cpp end *****************");
  return ocr_results;
}

static int align_to_32(int value, int max_value) {
  value = std::max(32, value);
  int aligned = static_cast<int>(std::round(value / 32.0f)) * 32;
  if (max_value > 0) {
    int max_aligned = std::max(32, (max_value / 32) * 32);
    aligned = std::min(aligned, max_aligned);
  }
  return std::max(32, aligned);
}

cv::Mat DetResizeImg(const cv::Mat img, int max_size_len,
                     std::vector<float> &ratio_hw) {
  int w = img.cols;
  int h = img.rows;

  float ratio = 1.f;
  int max_wh = std::max(w, h);
  if (max_size_len > 0 && max_wh > max_size_len) {
    ratio = static_cast<float>(max_size_len) /
            static_cast<float>(max_wh);
  }

  int resize_h = align_to_32(
      static_cast<int>(std::round(static_cast<float>(h) * ratio)),
      max_size_len);
  int resize_w = align_to_32(
      static_cast<int>(std::round(static_cast<float>(w) * ratio)),
      max_size_len);

  cv::Mat resize_img;
  cv::resize(img, resize_img, cv::Size(resize_w, resize_h), 0.f, 0.f,
             cv::INTER_LINEAR);

  ratio_hw.push_back(static_cast<float>(resize_h) / static_cast<float>(h));
  ratio_hw.push_back(static_cast<float>(resize_w) / static_cast<float>(w));
  return resize_img;
}

void OCR_PPredictor::infer_det(cv::Mat &origin, int max_size_len,
                               std::vector<OCRPredictResult> &ocr_results) {
  std::vector<float> mean = {0.485f, 0.456f, 0.406f};
  std::vector<float> scale = {1 / 0.229f, 1 / 0.224f, 1 / 0.225f};

  PredictorInput input = _det_predictor->get_first_input();

  std::vector<float> ratio_hw;
  cv::Mat input_image = DetResizeImg(origin, max_size_len, ratio_hw);
  input_image.convertTo(input_image, CV_32FC3, 1 / 255.0f);
  const float *dimg = reinterpret_cast<const float *>(input_image.data);
  int input_size = input_image.rows * input_image.cols;

  input.set_dims({1, 3, input_image.rows, input_image.cols});

  neon_mean_scale(dimg, input.get_mutable_float_data(), input_size, mean,
                  scale);
  LOGI("ocr cpp det shape %d,%d", input_image.rows, input_image.cols);
  std::vector<PredictorOutput> results = _det_predictor->infer();
  PredictorOutput &res = results.at(0);
  std::vector<std::vector<std::vector<int>>> filtered_box =
      calc_filtered_boxes(res.get_float_data(), res.get_size(),
                          input_image.rows, input_image.cols, origin);
  LOGI("ocr cpp det Filter_box size %ld", filtered_box.size());

  for (int i = 0; i < filtered_box.size(); i++) {
    LOGI("ocr cpp box  %d,%d,%d,%d,%d,%d,%d,%d", filtered_box[i][0][0],
         filtered_box[i][0][1], filtered_box[i][1][0], filtered_box[i][1][1],
         filtered_box[i][2][0], filtered_box[i][2][1], filtered_box[i][3][0],
         filtered_box[i][3][1]);
    OCRPredictResult res;
    res.points = filtered_box[i];
    ocr_results.push_back(res);
  }
}

namespace {

struct DecodedTokenRun {
  int index = 0;
  int start_step = 0;
  int end_step = 0;
  float confidence_sum = 0.f;
  int confidence_count = 0;
};

static cv::Mat build_foreground_mask(const cv::Mat &image) {
  if (image.empty()) {
    return cv::Mat{};
  }

  cv::Mat gray;
  if (image.channels() == 4) {
    cv::cvtColor(image, gray, cv::COLOR_BGRA2GRAY);
  } else if (image.channels() == 3) {
    cv::cvtColor(image, gray, cv::COLOR_BGR2GRAY);
  } else {
    gray = image.clone();
  }

  // 检测框通常包含一圈背景。使用边缘像素判断背景是亮还是暗，
  // 再用 Otsu 自动阈值提取字形前景，兼容白底黑字和黑底白字。
  double border_sum = 0.0;
  int border_count = 0;
  for (int x = 0; x < gray.cols; ++x) {
    border_sum += gray.at<unsigned char>(0, x);
    border_sum += gray.at<unsigned char>(gray.rows - 1, x);
    border_count += 2;
  }
  for (int y = 1; y + 1 < gray.rows; ++y) {
    border_sum += gray.at<unsigned char>(y, 0);
    border_sum += gray.at<unsigned char>(y, gray.cols - 1);
    border_count += 2;
  }
  const bool light_background = border_count == 0 ||
      border_sum / static_cast<double>(border_count) >= 127.0;

  cv::Mat mask;
  int threshold_type = light_background
      ? (cv::THRESH_BINARY_INV | cv::THRESH_OTSU)
      : (cv::THRESH_BINARY | cv::THRESH_OTSU);
  cv::threshold(gray, mask, 0, 255, threshold_type);

  // 仅移除极小孤立噪点，不做腐蚀/膨胀，避免细字体和数字“1”被吃掉。
  cv::Mat labels, stats, centroids;
  int component_count = cv::connectedComponentsWithStats(
      mask, labels, stats, centroids, 8, CV_32S);
  if (component_count <= 1) {
    return mask;
  }

  const int min_area = std::max(
      2,
      static_cast<int>(std::round(mask.rows * mask.cols * 0.00012f)));
  cv::Mat cleaned = cv::Mat::zeros(mask.size(), CV_8UC1);
  for (int label = 1; label < component_count; ++label) {
    int area = stats.at<int>(label, cv::CC_STAT_AREA);
    int height = stats.at<int>(label, cv::CC_STAT_HEIGHT);
    int width = stats.at<int>(label, cv::CC_STAT_WIDTH);
    bool meaningful_stroke = height >= std::max(2, mask.rows / 12) ||
                             width >= std::max(2, mask.rows / 12);
    if (area >= min_area && meaningful_stroke) {
      cleaned.setTo(255, labels == label);
    }
  }

  return cv::countNonZero(cleaned) > 0 ? cleaned : mask;
}

static int weighted_quantile_index(const std::vector<int> &counts,
                                   int total,
                                   float quantile) {
  if (counts.empty() || total <= 0) {
    return -1;
  }
  quantile = std::max(0.f, std::min(1.f, quantile));
  const int target = static_cast<int>(std::floor(total * quantile));
  int cumulative = 0;
  for (int i = 0; i < static_cast<int>(counts.size()); ++i) {
    cumulative += counts[i];
    if (cumulative > target) {
      return i;
    }
  }
  return static_cast<int>(counts.size()) - 1;
}

/**
 * 在指定 CTC token 单元中查找真实字形边界。
 *
 * V3 调优要点：
 * 1. 纵向使用 2% / 98% 前景像素分位数，过滤扫描噪点；
 * 2. 横向不再固定扩 1px，避免数字“1”、字母 i/l 等窄字符变宽；
 * 3. 最终边界被严格限制在 token 单元 [x_start, x_end) 内，绝不越过
 *    相邻 token 的 CTC 中点硬边界。
 */
static bool find_ink_bounds(const cv::Mat &mask,
                            int x_start,
                            int x_end,
                            cv::Rect &bounds,
                            float horizontal_low_quantile,
                            float horizontal_high_quantile,
                            float vertical_low_quantile,
                            float vertical_high_quantile,
                            int horizontal_padding_px,
                            int vertical_padding_px) {
  if (mask.empty() || mask.type() != CV_8UC1) {
    return false;
  }

  x_start = std::max(0, std::min(x_start, mask.cols - 1));
  x_end = std::max(x_start + 1, std::min(x_end, mask.cols));

  std::vector<int> column_counts(x_end - x_start, 0);
  std::vector<int> row_counts(mask.rows, 0);
  int total = 0;

  for (int y = 0; y < mask.rows; ++y) {
    const unsigned char *row = mask.ptr<unsigned char>(y);
    for (int x = x_start; x < x_end; ++x) {
      if (row[x] != 0) {
        column_counts[x - x_start]++;
        row_counts[y]++;
        total++;
      }
    }
  }

  if (total < 2) {
    return false;
  }

  int left = weighted_quantile_index(
      column_counts, total, horizontal_low_quantile);
  int right = weighted_quantile_index(
      column_counts, total, horizontal_high_quantile);
  int top = weighted_quantile_index(
      row_counts, total, vertical_low_quantile);
  int bottom = weighted_quantile_index(
      row_counts, total, vertical_high_quantile);
  if (left < 0 || right < left || top < 0 || bottom < top) {
    return false;
  }

  // 横向只允许配置的小余量，并严格夹在当前 token 的 CTC 单元内。
  left = std::max(x_start, x_start + left - horizontal_padding_px);
  right = std::min(x_end - 1, x_start + right + horizontal_padding_px);

  // 纵向仍保留少量抗锯齿余量，避免低清晰度文本被削掉上下笔画。
  top = std::max(0, top - vertical_padding_px);
  bottom = std::min(mask.rows - 1, bottom + vertical_padding_px);

  bounds = cv::Rect(left, top, right - left + 1, bottom - top + 1);
  return bounds.width > 0 && bounds.height > 0;
}

static std::array<float, 4> normalize_rect(const cv::Rect &rect,
                                           int width,
                                           int height) {
  const float safe_width = static_cast<float>(std::max(1, width));
  const float safe_height = static_cast<float>(std::max(1, height));
  float left = std::max(0.f, std::min(1.f, rect.x / safe_width));
  float top = std::max(0.f, std::min(1.f, rect.y / safe_height));
  float right = std::max(left, std::min(1.f,
      (rect.x + rect.width) / safe_width));
  float bottom = std::max(top, std::min(1.f,
      (rect.y + rect.height) / safe_height));
  return {{left, top, right, bottom}};
}

static void restore_box_after_180_rotation(std::array<float, 4> &box) {
  float left = box[0];
  float top = box[1];
  float right = box[2];
  float bottom = box[3];
  box[0] = 1.f - right;
  box[1] = 1.f - bottom;
  box[2] = 1.f - left;
  box[3] = 1.f - top;
}

} // namespace

void OCR_PPredictor::infer_rec(const cv::Mat &origin_img, int run_cls,
                               OCRPredictResult &ocr_result) {
  std::vector<float> mean = {0.5f, 0.5f, 0.5f};
  std::vector<float> scale = {1 / 0.5f, 1 / 0.5f, 1 / 0.5f};
  std::vector<int64_t> dims = {1, 3, 0, 0};

  PredictorInput input = _rec_predictor->get_first_input();

  const std::vector<std::vector<int>> &box = ocr_result.points;
  cv::Mat crop_img;
  if (!box.empty()) {
    crop_img = get_rotate_crop_image(origin_img, box);
  } else {
    crop_img = origin_img;
  }
  if (crop_img.empty()) {
    return;
  }

  bool rotated_180 = false;
  if (run_cls) {
    ClsPredictResult cls_res = infer_cls(crop_img);
    crop_img = cls_res.img;
    ocr_result.cls_score = cls_res.cls_score;
    ocr_result.cls_label = cls_res.cls_label;
    rotated_180 = cls_res.cls_label % 2 == 1 && cls_res.cls_score > 0.9f;
  }

  // 前景掩码保留在矫正后的原始文本行分辨率中，后续用于收紧每个字符。
  cv::Mat foreground_mask = build_foreground_mask(crop_img);

  float wh_ratio = float(crop_img.cols) / float(crop_img.rows);
  cv::Mat input_image = crnn_resize_img(crop_img, wh_ratio);
  input_image.convertTo(input_image, CV_32FC3, 1 / 255.0f);
  const float *dimg = reinterpret_cast<const float *>(input_image.data);
  int input_size = input_image.rows * input_image.cols;

  dims[2] = input_image.rows;
  dims[3] = input_image.cols;
  input.set_dims(dims);

  neon_mean_scale(dimg, input.get_mutable_float_data(), input_size, mean,
                  scale);

  std::vector<PredictorOutput> results = _rec_predictor->infer();
  const float *predict_batch = results.at(0).get_float_data();
  const std::vector<int64_t> predict_shape = results.at(0).get_shape();
  if (predict_shape.size() < 3 || predict_shape[1] <= 0 ||
      predict_shape[2] <= 0) {
    return;
  }

  const int time_steps = static_cast<int>(predict_shape[1]);
  const int class_count = static_cast<int>(predict_shape[2]);
  std::vector<DecodedTokenRun> token_runs;
  int last_index = 0;

  for (int n = 0; n < time_steps; ++n) {
    const float *step_begin = &predict_batch[n * class_count];
    const float *step_end = &predict_batch[(n + 1) * class_count];
    int argmax_idx = static_cast<int>(argmax(step_begin, step_end));
    float max_value = *std::max_element(step_begin, step_end);

    if (argmax_idx > 0) {
      if (n == 0 || argmax_idx != last_index) {
        DecodedTokenRun run;
        run.index = argmax_idx;
        run.start_step = n;
        run.end_step = n;
        run.confidence_sum = max_value;
        run.confidence_count = 1;
        token_runs.push_back(run);
      } else if (!token_runs.empty() && token_runs.back().index == argmax_idx) {
        token_runs.back().end_step = n;
        token_runs.back().confidence_sum += max_value;
        token_runs.back().confidence_count++;
      }
    }
    last_index = argmax_idx;
  }

  ocr_result.word_index.clear();
  ocr_result.char_boxes.clear();

  float score_sum = 0.f;
  for (const DecodedTokenRun &run : token_runs) {
    ocr_result.word_index.push_back(run.index);
    if (run.confidence_count > 0) {
      score_sum += run.confidence_sum /
          static_cast<float>(run.confidence_count);
    }
  }
  ocr_result.score = token_runs.empty()
      ? 0.f
      : score_sum / static_cast<float>(token_runs.size());

  cv::Rect global_ink_bounds;
  const bool has_global_ink = find_ink_bounds(
      foreground_mask,
      0,
      foreground_mask.cols,
      global_ink_bounds,
      0.0025f,
      0.9975f,
      0.02f,
      0.98f,
      0,
      1);

  std::vector<std::array<float, 4>> aligned_boxes;
  std::vector<std::array<float, 2>> hard_x_ranges;
  aligned_boxes.reserve(token_runs.size());
  hard_x_ranges.reserve(token_runs.size());

  for (int i = 0; i < static_cast<int>(token_runs.size()); ++i) {
    const DecodedTokenRun &current = token_runs[i];

    // 将相邻 CTC 激活区之间的 blank 区域各分一半，形成字符候选区。
    // 这个候选区同时作为最终字符框不可越过的硬边界。
    float left_step = 0.f;
    float right_step = static_cast<float>(time_steps);
    if (i > 0) {
      left_step = (token_runs[i - 1].end_step + current.start_step + 1) * 0.5f;
    }
    if (i + 1 < static_cast<int>(token_runs.size())) {
      right_step = (current.end_step + token_runs[i + 1].start_step + 1) * 0.5f;
    }

    int x_start = static_cast<int>(std::floor(
        left_step / static_cast<float>(time_steps) * crop_img.cols));
    int x_end = static_cast<int>(std::ceil(
        right_step / static_cast<float>(time_steps) * crop_img.cols));
    x_start = std::max(0, std::min(x_start, crop_img.cols - 1));
    x_end = std::max(x_start + 1, std::min(x_end, crop_img.cols));

    const float hard_left = static_cast<float>(x_start) /
        static_cast<float>(std::max(1, crop_img.cols));
    const float hard_right = static_cast<float>(x_end) /
        static_cast<float>(std::max(1, crop_img.cols));

    cv::Rect ink_bounds;
    std::array<float, 4> normalized;
    if (find_ink_bounds(
            foreground_mask,
            x_start,
            x_end,
            ink_bounds,
            0.005f,
            0.995f,
            0.02f,
            0.98f,
            0,
            1)) {
      normalized = normalize_rect(ink_bounds, crop_img.cols, crop_img.rows);
    } else {
      int fallback_top = has_global_ink ? global_ink_bounds.y : 0;
      int fallback_height = has_global_ink
          ? global_ink_bounds.height
          : crop_img.rows;

      /*
       * V3.1：前景像素提取偶尔会在极细字符（最常见是数字“1”）上失败。
       * 旧逻辑会退回整个 CTC 硬单元 [x_start, x_end)，而硬单元包含相邻
       * token 之间一半的 blank 区域；当字符左侧恰好有较大空格时，高亮框
       * 就会向空白处异常扩张。
       *
       * 回退时改用当前 token 真正激活的 CTC 时间步，而不是 blank-inclusive
       * 硬单元。只增加少量时间步级 padding，并保留一个与行高相关的最小宽度，
       * 最终仍被 [x_start, x_end) 严格夹紧。这样既能收回空白区，又不会把
       * 低清晰度字符裁成一条线。
       */
      const float pixels_per_step = static_cast<float>(crop_img.cols) /
          static_cast<float>(std::max(1, time_steps));
      int active_left = static_cast<int>(std::floor(
          current.start_step * pixels_per_step));
      int active_right = static_cast<int>(std::ceil(
          (current.end_step + 1) * pixels_per_step));

      const int step_padding = std::max(
          1,
          static_cast<int>(std::round(pixels_per_step * 0.35f)));
      active_left = std::max(x_start, active_left - step_padding);
      active_right = std::min(x_end, active_right + step_padding);

      const int hard_width = std::max(1, x_end - x_start);
      const int minimum_fallback_width = std::min(
          hard_width,
          std::max(2, static_cast<int>(std::round(crop_img.rows * 0.45f))));
      int fallback_width = std::max(1, active_right - active_left);

      if (fallback_width < minimum_fallback_width) {
        const float active_center = (active_left + active_right) * 0.5f;
        int widened_left = static_cast<int>(std::floor(
            active_center - minimum_fallback_width * 0.5f));
        int widened_right = widened_left + minimum_fallback_width;

        if (widened_left < x_start) {
          widened_right += x_start - widened_left;
          widened_left = x_start;
        }
        if (widened_right > x_end) {
          widened_left -= widened_right - x_end;
          widened_right = x_end;
        }

        active_left = std::max(x_start, widened_left);
        active_right = std::min(x_end, widened_right);
      }

      if (active_right <= active_left) {
        active_left = x_start;
        active_right = x_end;
      }

      normalized = normalize_rect(
          cv::Rect(
              active_left,
              fallback_top,
              active_right - active_left,
              fallback_height),
          crop_img.cols,
          crop_img.rows);
    }

    // native 保留原始紧致字形框，不再横向扩张。最终 0.25px / 0.60px
    // 的查询级安全余量由 Java 按“单 token / 多 token”分别处理。
    normalized[0] = std::max(hard_left, normalized[0]);
    normalized[2] = std::min(hard_right, normalized[2]);

    if (normalized[2] <= normalized[0]) {
      normalized[0] = hard_left;
      normalized[2] = hard_right;
    }

    aligned_boxes.push_back(normalized);
    hard_x_ranges.push_back({{hard_left, hard_right}});
  }

  // 再用相邻 token 中心的中点做一次防重叠夹紧。只有两个字形框实际
  // 重叠时才生效，正常字距不会被压缩。
  const std::vector<std::array<float, 4>> original_boxes = aligned_boxes;
  for (int i = 0; i < static_cast<int>(aligned_boxes.size()); ++i) {
    std::array<float, 4> &box = aligned_boxes[i];
    const std::array<float, 4> &original = original_boxes[i];
    const float current_center = (original[0] + original[2]) * 0.5f;

    if (i > 0) {
      const std::array<float, 4> &previous = original_boxes[i - 1];
      if (previous[2] > original[0]) {
        const float previous_center = (previous[0] + previous[2]) * 0.5f;
        const float boundary = (previous_center + current_center) * 0.5f;
        box[0] = std::max(box[0], boundary);
      }
      box[0] = std::max(box[0], hard_x_ranges[i][0]);
    }

    if (i + 1 < static_cast<int>(original_boxes.size())) {
      const std::array<float, 4> &next = original_boxes[i + 1];
      if (original[2] > next[0]) {
        const float next_center = (next[0] + next[2]) * 0.5f;
        const float boundary = (current_center + next_center) * 0.5f;
        box[2] = std::min(box[2], boundary);
      }
      box[2] = std::min(box[2], hard_x_ranges[i][1]);
    }

    if (box[2] <= box[0]) {
      box[0] = original[0];
      box[2] = original[2];
    }
  }

  for (std::array<float, 4> box : aligned_boxes) {
    if (rotated_180) {
      restore_box_after_180_rotation(box);
    }
    ocr_result.char_boxes.push_back(box);
  }

  LOGI("ocr cpp rec word size %ld, aligned boxes %ld",
       ocr_result.word_index.size(), ocr_result.char_boxes.size());
}

ClsPredictResult OCR_PPredictor::infer_cls(const cv::Mat &img, float thresh) {
  std::vector<float> mean = {0.5f, 0.5f, 0.5f};
  std::vector<float> scale = {1 / 0.5f, 1 / 0.5f, 1 / 0.5f};
  std::vector<int64_t> dims = {1, 3, 0, 0};

  PredictorInput input = _cls_predictor->get_first_input();

  cv::Mat input_image = cls_resize_img(img);
  input_image.convertTo(input_image, CV_32FC3, 1 / 255.0f);
  const float *dimg = reinterpret_cast<const float *>(input_image.data);
  int input_size = input_image.rows * input_image.cols;

  dims[2] = input_image.rows;
  dims[3] = input_image.cols;
  input.set_dims(dims);

  neon_mean_scale(dimg, input.get_mutable_float_data(), input_size, mean,
                  scale);

  std::vector<PredictorOutput> results = _cls_predictor->infer();

  const float *scores = results.at(0).get_float_data();
  float score = 0;
  int label = 0;
  for (int64_t i = 0; i < results.at(0).get_size(); i++) {
    LOGI("ocr cpp cls output scores [%f]", scores[i]);
    if (scores[i] > score) {
      score = scores[i];
      label = i;
    }
  }
  cv::Mat srcimg;
  img.copyTo(srcimg);
  if (label % 2 == 1 && score > thresh) {
    cv::rotate(srcimg, srcimg, 1);
  }
  ClsPredictResult res;
  res.cls_label = label;
  res.cls_score = score;
  res.img = srcimg;
  LOGI("ocr cpp cls word cls %d, %f", label, score);
  return res;
}

std::vector<std::vector<std::vector<int>>>
OCR_PPredictor::calc_filtered_boxes(const float *pred, int pred_size,
                                    int output_height, int output_width,
                                    const cv::Mat &origin) {
  const double threshold = 0.3;
  const double maxvalue = 1;

  cv::Mat pred_map = cv::Mat::zeros(output_height, output_width, CV_32F);
  memcpy(pred_map.data, pred, pred_size * sizeof(float));
  cv::Mat cbuf_map;
  pred_map.convertTo(cbuf_map, CV_8UC1);

  cv::Mat bit_map;
  cv::threshold(cbuf_map, bit_map, threshold, maxvalue, cv::THRESH_BINARY);

  std::vector<std::vector<std::vector<int>>> boxes =
      boxes_from_bitmap(pred_map, bit_map);
  float ratio_h = output_height * 1.0f / origin.rows;
  float ratio_w = output_width * 1.0f / origin.cols;
  std::vector<std::vector<std::vector<int>>> filter_boxes =
      filter_tag_det_res(boxes, ratio_h, ratio_w, origin);
  return filter_boxes;
}

std::vector<int>
OCR_PPredictor::postprocess_rec_word_index(const PredictorOutput &res) {
  const int *rec_idx = res.get_int_data();
  const std::vector<std::vector<uint64_t>> rec_idx_lod = res.get_lod();

  std::vector<int> pred_idx;
  for (int n = int(rec_idx_lod[0][0]); n < int(rec_idx_lod[0][1] * 2); n += 2) {
    pred_idx.emplace_back(rec_idx[n]);
  }
  return pred_idx;
}

float OCR_PPredictor::postprocess_rec_score(const PredictorOutput &res) {
  const float *predict_batch = res.get_float_data();
  const std::vector<int64_t> predict_shape = res.get_shape();
  const std::vector<std::vector<uint64_t>> predict_lod = res.get_lod();
  int blank = predict_shape[1];
  float score = 0.f;
  int count = 0;
  for (int n = predict_lod[0][0]; n < predict_lod[0][1] - 1; n++) {
    int argmax_idx = argmax(predict_batch + n * predict_shape[1],
                            predict_batch + (n + 1) * predict_shape[1]);
    float max_value = predict_batch[n * predict_shape[1] + argmax_idx];
    if (blank - 1 - argmax_idx > 1e-5) {
      score += max_value;
      count += 1;
    }
  }
  if (count == 0) {
    LOGE("calc score count 0");
  } else {
    score /= count;
  }
  LOGI("calc score: %f", score);
  return score;
}

NET_TYPE OCR_PPredictor::get_net_flag() const { return NET_OCR; }
} // namespace ppredictor
