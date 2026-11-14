export type SmartScanStage = 'FACE_STAGE' | 'BODY_STAGE' | 'HAIR_STAGE';

export interface StageFrameGuidance {
  brightness: number;
  motionScore: number;
  sharpnessScore: number;
  isReadyToCapture: boolean;
  guidanceAr: string;
  boundingBoxNormalized?: { x: number; y: number; width: number; height: number };
  frameHash: string;
}

function computeFrameMetrics(video: HTMLVideoElement, prevGray: Uint8Array | null) {
  const w = 160;
  const h = 120;
  const canvas = document.createElement('canvas');
  canvas.width = w;
  canvas.height = h;
  const ctx = canvas.getContext('2d', { willReadFrequently: true })!;
  ctx.drawImage(video, 0, 0, w, h);

  const imgData = ctx.getImageData(0, 0, w, h).data;
  const gray = new Uint8Array(w * h);

  let lumSum = 0;
  for (let i = 0; i < w * h; i++) {
    const idx = i * 4;
    const lum = Math.round(0.299 * imgData[idx] + 0.587 * imgData[idx + 1] + 0.114 * imgData[idx + 2]);
    gray[i] = lum;
    lumSum += lum;
  }
  const brightness = lumSum / (w * h);

  let motionSum = 0;
  if (prevGray && prevGray.length === gray.length) {
    for (let i = 0; i < gray.length; i += 2) {
      motionSum += Math.abs(gray[i] - prevGray[i]);
    }
  }
  const motionScore = prevGray ? motionSum / (gray.length / 2) : 18;

  let edgeSum = 0;
  let edgeCount = 0;
  for (let y = 16; y < h - 16; y += 2) {
    for (let x = 24; x < w - 24; x += 2) {
      const c = gray[y * w + x];
      edgeSum += Math.abs(c - gray[y * w + x + 1]) + Math.abs(c - gray[(y + 1) * w + x]);
      edgeCount++;
    }
  }
  const sharpnessScore = edgeCount > 0 ? edgeSum / edgeCount : 0;

  let hash = '';
  for (let i = 0; i < 16; i++) {
    hash += Math.floor(gray[Math.floor((i / 16) * gray.length)] / 32).toString(16);
  }

  return { w, h, canvas, imgData, gray, brightness, motionScore, sharpnessScore, hash };
}

/**
 * Stage-aware live camera analyzer for SABQ SMART SCAN (Face -> Full Body -> Hair & Scalp).
 * Runs at ~4-5 fps on 160x120 to minimize CPU, heat, and battery usage.
 */
export async function evaluateSmartScanStageFrame(
  stage: SmartScanStage,
  video: HTMLVideoElement,
  prevGray: Uint8Array | null
): Promise<{ guidance: StageFrameGuidance; grayFrame: Uint8Array }> {
  const { w, h, canvas, imgData, gray, brightness, motionScore, sharpnessScore, hash } =
    computeFrameMetrics(video, prevGray);

  if (brightness < 46) {
    return {
      guidance: {
        brightness,
        motionScore,
        sharpnessScore,
        isReadyToCapture: false,
        guidanceAr: 'الإضاءة ضعيفة، انتقل إلى مكان أكثر إضاءة.',
        frameHash: hash,
      },
      grayFrame: gray,
    };
  }
  if (brightness > 242) {
    return {
      guidance: {
        brightness,
        motionScore,
        sharpnessScore,
        isReadyToCapture: false,
        guidanceAr: 'الإضاءة شديدة جدًا، خفف الإضاءة المباشرة.',
        frameHash: hash,
      },
      grayFrame: gray,
    };
  }

  // Stage 1: Face Detection, distance, centering & stability
  if (stage === 'FACE_STAGE') {
    let boxNorm: StageFrameGuidance['boundingBoxNormalized'];
    let faceFound = false;

    const FaceDetectorCtor = (window as any).FaceDetector;
    if (FaceDetectorCtor) {
      try {
        const detector = new FaceDetectorCtor({ fastMode: true, maxDetectedFaces: 1 });
        const faces = await detector.detect(canvas);
        if (Array.isArray(faces) && faces.length > 0) {
          faceFound = true;
          const b = faces[0].boundingBox;
          boxNorm = { x: b.x / w, y: b.y / h, width: b.width / w, height: b.height / h };
        }
      } catch {
        // fallback below
      }
    }

    if (!faceFound) {
      // Skin-tone central box heuristic fallback
      let minX = w, maxX = 0, minY = h, maxY = 0, skinHits = 0;
      for (let y = Math.round(h * 0.1); y < Math.round(h * 0.9); y++) {
        for (let x = Math.round(w * 0.15); x < Math.round(w * 0.85); x++) {
          const idx = (y * w + x) * 4;
          const r = imgData[idx], g = imgData[idx + 1], b = imgData[idx + 2];
          if (r > 60 && g > 38 && b > 20 && r > g && r > b && Math.abs(r - g) > 10) {
            skinHits++;
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
          }
        }
      }
      if (skinHits > w * h * 0.06) {
        faceFound = true;
        boxNorm = {
          x: minX / w,
          y: minY / h,
          width: (maxX - minX) / w,
          height: (maxY - minY) / h,
        };
      }
    }

    let guidanceAr = 'انظر مباشرة إلى الكاميرا واجعل وجهك في منتصف الإطار.';
    let ready = false;

    if (!faceFound || !boxNorm) {
      guidanceAr = 'اجعل وجهك في منتصف الإطار أمام الكاميرا.';
    } else {
      const centerX = boxNorm.x + boxNorm.width / 2;
      const centerY = boxNorm.y + boxNorm.height / 2;
      if (boxNorm.width < 0.18 || boxNorm.height < 0.22) {
        guidanceAr = 'اقترب قليلًا من الكاميرا.';
      } else if (boxNorm.width > 0.68 || boxNorm.height > 0.82) {
        guidanceAr = 'ابتعد قليلًا عن الكاميرا.';
      } else if (centerX < 0.34 || centerX > 0.66) {
        guidanceAr = 'اجعل وجهك في منتصف الإطار.';
      } else if (centerY < 0.28) {
        guidanceAr = 'اخفض رأسك قليلًا للوسط.';
      } else if (centerY > 0.72) {
        guidanceAr = 'ارفع رأسك قليلًا وانظر مباشرة إلى الكاميرا.';
      } else if (motionScore > 12) {
        guidanceAr = 'اثبت قليلًا، جارٍ تجهيز الصورة...';
      } else if (sharpnessScore < 5.2) {
        guidanceAr = 'الصورة غير واضحة، اثبت قليلًا...';
      } else {
        guidanceAr = 'ممتاز! اثبت لحظة — جارٍ التقاط صورة الوجه تلقائيًا...';
        ready = true;
      }
    }

    return {
      guidance: {
        brightness,
        motionScore,
        sharpnessScore,
        isReadyToCapture: ready,
        guidanceAr,
        boundingBoxNormalized: boxNorm,
        frameHash: hash,
      },
      grayFrame: gray,
    };
  }

  // Stage 2: Full Body Pose & Framing (head-to-feet occupancy, vertical symmetry & stability)
  if (stage === 'BODY_STAGE') {
    // Compare center vertical strip against outer background edges to measure silhouette height span
    let topEdge = h;
    let bottomEdge = 0;
    let bodyBands = 0;

    for (let y = 4; y < h - 4; y += 2) {
      let rowEnergy = 0;
      for (let x = Math.round(w * 0.28); x < Math.round(w * 0.72); x += 2) {
        rowEnergy += Math.abs(gray[y * w + x] - gray[y * w + (x + 2)]);
      }
      if (rowEnergy / 22 > 8) {
        bodyBands++;
        if (y < topEdge) topEdge = y;
        if (y > bottomEdge) bottomEdge = y;
      }
    }

    const topRatio = topEdge / h;
    const bottomRatio = bottomEdge / h;
    const spanRatio = Math.max(0, bottomRatio - topRatio);

    let guidanceAr = 'اجعل جسمك كاملًا داخل الإطار وقف بشكل مستقيم.';
    let ready = false;

    if (bodyBands < 12) {
      guidanceAr = 'قف في منتصف الإطار أمام الكاميرا بحيث يظهر جسمك كاملًا.';
    } else if (topRatio < 0.05) {
      guidanceAr = 'ارفع الهاتف قليلًا أو ابتعد ليظهر الرأس كاملًا داخل الإطار.';
    } else if (bottomRatio > 0.95) {
      guidanceAr = 'ابتعد قليلًا واجعل قدميك ظاهرتين داخل الإطار.';
    } else if (spanRatio < 0.48) {
      guidanceAr = 'اقترب قليلًا ليظهر جسمك بوضوح أكبر.';
    } else if (motionScore > 12) {
      guidanceAr = 'اثبت في مكانك بشكل مستقيم لحظة...';
    } else if (sharpnessScore < 4.5) {
      guidanceAr = 'ثبّت الهاتف لوضوح الصورة...';
    } else {
      guidanceAr = 'وضعية ممتازة! جارٍ التقاط صورة الجسم تلقائيًا...';
      ready = true;
    }

    return {
      guidance: {
        brightness,
        motionScore,
        sharpnessScore,
        isReadyToCapture: ready,
        guidanceAr,
        frameHash: hash,
      },
      grayFrame: gray,
    };
  }

  // Stage 3: Hair & Scalp Guidance (upper-half occupancy, closeness, sharpness, stability)
  let upperEdges = 0;
  let upperCount = 0;
  for (let y = 6; y < Math.round(h * 0.55); y += 2) {
    for (let x = Math.round(w * 0.2); x < Math.round(w * 0.8); x += 2) {
      upperEdges += Math.abs(gray[y * w + x] - gray[(y + 1) * w + x]);
      upperCount++;
    }
  }
  const upperSharpness = upperCount > 0 ? upperEdges / upperCount : 0;

  let guidanceAr = 'قرّب الرأس وأظهر خط الشعر ومنطقة فروة الرأس بوضوح.';
  let ready = false;

  if (upperSharpness < 4.8) {
    guidanceAr = 'قرّب الرأس قليلًا من الكاميرا وحسّن الإضاءة لإظهار الشعر وخط الرأس.';
  } else if (motionScore > 11) {
    guidanceAr = 'اثبت قليلًا مع إمالة الرأس للأمام لإظهار الشعر بوضوح...';
  } else {
    guidanceAr = 'منطقة الشعر واضحة — جارٍ التقاط لقطة الشعر تلقائيًا...';
    ready = true;
  }

  return {
    guidance: {
      brightness,
      motionScore,
      sharpnessScore: upperSharpness,
      isReadyToCapture: ready,
      guidanceAr,
      frameHash: hash,
    },
    grayFrame: gray,
  };
}

export function captureVideoFrameAndHairCrop(video: HTMLVideoElement): {
  fullFrameDataUrl: string;
  hairCropDataUrl: string;
} {
  const srcW = video.videoWidth || 640;
  const srcH = video.videoHeight || 480;
  const maxDim = 800;
  let vw = srcW;
  let vh = srcH;
  if (vw > maxDim || vh > maxDim) {
    if (vw >= vh) {
      vh = Math.max(1, Math.round((srcH * maxDim) / srcW));
      vw = maxDim;
    } else {
      vw = Math.max(1, Math.round((srcW * maxDim) / srcH));
      vh = maxDim;
    }
  }

  const fullCanvas = document.createElement('canvas');
  fullCanvas.width = vw;
  fullCanvas.height = vh;
  fullCanvas.getContext('2d')!.drawImage(video, 0, 0, vw, vh);

  // Upper 62% centered 76% preserves full hairline, top hair, and visible scalp
  const hairCanvas = document.createElement('canvas');
  const hx = Math.round(vw * 0.12);
  const hw = Math.max(1, Math.round(vw * 0.76));
  const hh = Math.max(1, Math.round(vh * 0.62));
  hairCanvas.width = hw;
  hairCanvas.height = hh;
  hairCanvas.getContext('2d')!.drawImage(fullCanvas, hx, 0, hw, hh, 0, 0, hw, hh);

  return {
    fullFrameDataUrl: fullCanvas.toDataURL('image/jpeg', 0.80),
    hairCropDataUrl: hairCanvas.toDataURL('image/jpeg', 0.80),
  };
}
