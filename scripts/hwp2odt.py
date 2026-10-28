# HWP(5.x) -> ODT 변환 래퍼 (HwpToPdfConverter가 호출)
# pyhwp의 RelaxNG 검증을 생략한다: 최신 lxml과의 호환 문제로 검증 단계가 항상 실패하는데,
# 검증은 산출물 품질과 무관(스키마 형식 검사)이라 우회해도 LibreOffice가 정상적으로 읽는다.
#
# 한컴오피스가 아닌 도구로 만든 HWP는 한컴오피스가 항상 써 넣는 기록이 빠져 있을 수 있다.
# 한컴오피스는 그래도 열지만 pyhwp는 멈춘다(LibreOffice도 직접 못 연다). 빠진 기록만 메운다:
# - 문서 요약 정보(\005HwpSummaryInformation)가 없으면 건너뛴다 — 제목·작성자 같은 메타데이터라 본문과 무관
# - 문단에 글자 모양 기록이 없으면 기본 글자 모양(0번)으로 본다 — pyhwp가 줄 정보 없는 문단에 하는 처리와 같다
# 둘 다 기록이 없을 때만 동작해 정상 파일의 결과는 바뀌지 않는다.
#
# 사용: python3 hwp2odt.py <입력.hwp> <출력.odt>
import sys
from itertools import chain

from hwp5 import plat

plat.get_relaxng_compile = lambda: None

from hwp5 import xmlmodel  # noqa: E402
from hwp5.hwp5odt import main  # noqa: E402  (모듈 로드 전에 검증 우회가 걸려야 함)

SUMMARY_INFO = '\005HwpSummaryInformation'

_events = xmlmodel.Hwp5File.events
_merge = xmlmodel.merge_paragraph_text_charshape_lineseg


def _events_without_summary(self, **kwargs):
    if SUMMARY_INFO in self:
        return _events(self, **kwargs)
    # 원본 Hwp5File.events에서 summaryinfo만 뺀 것
    if 'embedbin' in kwargs and kwargs['embedbin'] and 'BinData' in self:
        kwargs['embedbin'] = self['BinData']
    else:
        kwargs.pop('embedbin', None)
    events = chain(self.docinfo.events(**kwargs), self.text.events(**kwargs))
    hwpdoc = xmlmodel.HwpDoc, dict(version=self.header.version), dict()
    events = xmlmodel.wrap_modelevents(hwpdoc, events)
    return xmlmodel.give_elements_unique_id(events)


def _merge_with_default_charshape(paratext, paracharshape, paralineseg):
    if paracharshape is None:
        paracharshape = (xmlmodel.ParaCharShape, dict(charshapes=[(0, 0)]), dict())
    return _merge(paratext, paracharshape, paralineseg)


xmlmodel.Hwp5File.events = _events_without_summary
xmlmodel.merge_paragraph_text_charshape_lineseg = _merge_with_default_charshape

sys.argv = ["hwp5odt", "--output", sys.argv[2], sys.argv[1]]
sys.exit(main())
